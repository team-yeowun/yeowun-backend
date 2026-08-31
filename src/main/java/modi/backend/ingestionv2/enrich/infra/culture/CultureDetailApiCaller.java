package modi.backend.ingestionv2.enrich.infra.culture;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import modi.backend.ingestionv2.enrich.domain.EnrichErrorCode;
import modi.backend.support.error.CoreException;

/**
 * 문화포털 상세(detail2) 접속 담당.
 *
 * <ul>
 *   <li>접속 설정(base URL·인증키·타임아웃) 소유 - 수집의 목록 창구와 같은 값을 읽지만 요청선은 격벽마다 따로 둔다</li>
 *   <li>벤더 실패 판정 포함 - 이 원천은 키 오류·한도 초과도 HTTP 200 + resultCode 로 보냄</li>
 *   <li>전송·역직렬화·벤더 실패를 하나의 오류 어휘로 번역 (재시도 판정은 상위 몫)</li>
 * </ul>
 */
@Component
public class CultureDetailApiCaller {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	private final RestClient restClient;
	private final String serviceKey;

	public CultureDetailApiCaller(
			@Value("${app.public-data.culture.base-url:https://apis.data.go.kr/B553457/cultureinfo}") String baseUrl,
			@Value("${app.public-data.culture.service-key:}") String serviceKey,
			@Value("${app.public-data.culture.timeout-seconds:15}") long timeoutSeconds) {
		this.serviceKey = serviceKey;
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

	/** 상세 1건 - 상세는 페이징이 없어 단건 호출이 곧 결과. */
	public CultureDetailApiDto.DetailResponse detail(String vendorKey) {
		requireServiceKey();
		CultureDetailApiDto.DetailResponse response = exchange(() -> restClient.get()
				.uri(uriBuilder -> uriBuilder.path("/detail2")
						.queryParam("serviceKey", serviceKey)
						.queryParam("seq", vendorKey)
						.build())
				.retrieve()
				.body(CultureDetailApiDto.DetailResponse.class));
		if (response == null || !response.isSuccess()) {
			throw new CoreException(EnrichErrorCode.DETAIL_FETCH_FAILED,
					"문화포털 상세 비정상 응답: " + (response == null ? "응답 없음" : response.vendorFailure()));
		}
		return response;
	}

	private void requireServiceKey() {
		if (serviceKey == null || serviceKey.isBlank()) {
			throw new CoreException(EnrichErrorCode.DETAIL_FETCH_FAILED, "문화포털 인증키 미설정");
		}
	}

	private <T> T exchange(Supplier<T> call) {
		try {
			return call.get();
		} catch (RuntimeException failure) {
			throw new CoreException(EnrichErrorCode.DETAIL_FETCH_FAILED,
					"문화포털 상세 호출 실패: " + failure.getMessage(), failure);
		}
	}
}
