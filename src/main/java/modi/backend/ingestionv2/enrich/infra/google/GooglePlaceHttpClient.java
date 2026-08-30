package modi.backend.ingestionv2.enrich.infra.google;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.enrich.domain.EnrichErrorCode;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceData;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceHoursClient;
import modi.backend.support.error.CoreException;

/**
 * 구글 Places(New) Text Search 어댑터.
 *
 * <ul>
 *   <li>장소명+주소로 1콜(/v1/places:searchText) - 영업시간까지 한 번에</li>
 *   <li>호출은 트랜잭션 밖 전제 - 콜 1건 = 감사 1행(유료 API라 건수 추적이 곧 비용 추적)</li>
 *   <li>후보 없음은 예외가 아니라 PlaceData.notFound()(감사 NO_DATA) - 조회했다는 사실이 완비의 근거</li>
 *   <li>api-key 미설정은 호출 전 실패 - 부르지 않은 호출을 "못 찾음"으로 위장하지 않음</li>
 * </ul>
 */
@Component
public class GooglePlaceHttpClient implements PlaceHoursClient {

	/** New API 필수 헤더 - 받을 필드만(없으면 400). */
	private static final String FIELD_MASK =
			"places.id,places.displayName,places.formattedAddress,places.regularOpeningHours";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	/** 감사 request_key 컬럼 상한. */
	private static final int REQUEST_KEY_MAX = 500;

	private final RestClient restClient;
	private final ExternalApiCallLogRecorder callLogRecorder;
	private final String apiKey;
	private final String languageCode;
	private final String regionCode;

	public GooglePlaceHttpClient(ExternalApiCallLogRecorder callLogRecorder,
			@Value("${app.exhibition.place-hours.base-url:https://places.googleapis.com}") String baseUrl,
			@Value("${app.exhibition.place-hours.api-key:}") String apiKey,
			@Value("${app.exhibition.place-hours.language-code:ko}") String languageCode,
			@Value("${app.exhibition.place-hours.region-code:KR}") String regionCode,
			@Value("${app.exhibition.place-hours.timeout-seconds:10}") long timeoutSeconds) {
		this.callLogRecorder = callLogRecorder;
		this.apiKey = apiKey;
		this.languageCode = languageCode;
		this.regionCode = regionCode;
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

	@Override
	public PlaceData fetchPlace(String placeName, String placeAddress) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new CoreException(EnrichErrorCode.PLACE_FETCH_FAILED, "구글 Places api-key 미설정");
		}
		String requestKey = requestKey(placeName, placeAddress);
		LocalDateTime calledAt = IngestionClock.now();
		Optional<GooglePlaceApiDto.Place> place;
		try {
			place = search(query(placeName, placeAddress));
		} catch (RuntimeException failure) {
			record(requestKey, ExternalApiOutcome.FAILED, calledAt);
			throw new CoreException(EnrichErrorCode.PLACE_FETCH_FAILED,
					"구글 Places 호출 실패: " + failure.getMessage(), failure);
		}
		if (place.isEmpty()) {
			record(requestKey, ExternalApiOutcome.NO_DATA, calledAt);
			return PlaceData.notFound();
		}
		record(requestKey, ExternalApiOutcome.SUCCESS, calledAt);
		return place.get().toPlaceData();
	}

	private Optional<GooglePlaceApiDto.Place> search(String textQuery) {
		GooglePlaceApiDto.SearchTextResponse response = restClient.post()
				.uri("/v1/places:searchText")
				// 인증키는 URL 노출을 피해 헤더로.
				.header("X-Goog-Api-Key", apiKey)
				.header("X-Goog-FieldMask", FIELD_MASK)
				.body(new GooglePlaceApiDto.SearchTextRequest(textQuery, languageCode, regionCode))
				.retrieve()
				.body(GooglePlaceApiDto.SearchTextResponse.class);
		return response == null ? Optional.empty() : response.firstPlace();
	}

	/** 장소명이 있으면 주소와 함께 질의(매칭 정확도 상승). 없으면 주소만. */
	private static String query(String placeName, String placeAddress) {
		String address = placeAddress == null ? "" : placeAddress.trim();
		if (placeName == null || placeName.isBlank()) {
			return address;
		}
		return (placeName.trim() + " " + address).trim();
	}

	/** 감사 대상 식별 - 어느 장소를 물었는지. 컬럼 상한을 넘기면 자른다. */
	private static String requestKey(String placeName, String placeAddress) {
		String key = query(placeName, placeAddress);
		return key.length() <= REQUEST_KEY_MAX ? key : key.substring(0, REQUEST_KEY_MAX);
	}

	private void record(String requestKey, ExternalApiOutcome outcome, LocalDateTime calledAt) {
		callLogRecorder.record(
				ExternalApiCallLog.of(ApiCallSource.INGESTION, ExternalApi.GOOGLE, requestKey, outcome, calledAt));
	}
}
