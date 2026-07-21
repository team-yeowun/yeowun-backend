package modi.backend.ingestion.infra.google;

import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.PlaceKey;
import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.ExternalApiOutcome;
import modi.backend.ingestion.domain.data.PlaceHoursFetch;
import modi.backend.ingestion.domain.entity.ExternalApiCallLog;
import modi.backend.ingestion.domain.port.ExternalApiCallLogRepository;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.properties.PlaceHoursProperties;

/**
 * 구글 Places(New) 실호출 영업시간 조회기. 장소명+주소로 Text Search 1콜을 보내 {@code regularOpeningHours}를 받는다.
 * <p>
 * 계약({@link PlaceHoursProvider}): 미발견은 {@link Optional#empty()}, 전송 오류(RestClient 예외)는 전파해 상위가 스킵/재시도한다.
 * 장소는 찾았으나 영업시간이 없으면 {@link WeeklyOpeningHours#empty()}를 담아 반환한다(장소 확인은 됐으므로 재조회 대상에서 빠지게).
 * 운영에서만 선택되며(mock 기본), 키 미설정 시엔 애초에 {@code MockPlaceHoursProvider}가 @Primary로 선택된다.
 * <p>
 * <b>파싱은 여기 없다</b> — 구글 어휘(0=일요일 day 인덱스·hour/minute)를 도메인 값으로 옮기는 일은 응답 record
 * ({@link GoogleMapsDto.Place#toFetch()})가 자기 몫으로 한다(culture의 {@code Item.toCatalog()}와 같은 모양).
 * 이 클래스는 <b>보내고 받는 데까지</b>다.
 */
@Component
public class GooglePlaceHoursProvider implements PlaceHoursProvider {

	private static final Logger log = LoggerFactory.getLogger(GooglePlaceHoursProvider.class);

	/** New API 필수 헤더 — 받을 필드만. 영업시간까지 한 콜로 받는다(2단계 Place Details 불필요). */
	private static final String FIELD_MASK =
			"places.id,places.displayName,places.formattedAddress,places.regularOpeningHours";

	private final GoogleMapsApi googleMapsApi;
	private final PlaceHoursProperties properties;
	/** 외부 호출 감사 — 호출량 추이용(유료 여부는 api 값이 말해준다). */
	private final ExternalApiCallLogRepository externalApiCallRepository;

	public GooglePlaceHoursProvider(GoogleMapsApi googleMapsApi, PlaceHoursProperties properties,
			ExternalApiCallLogRepository externalApiCallRepository) {
		this.googleMapsApi = googleMapsApi;
		this.properties = properties;
		this.externalApiCallRepository = externalApiCallRepository;
	}

	@Override
	public Optional<PlaceHoursFetch> fetch(String placeName, String placeAddr) {
		GoogleMapsDto.SearchTextRequest request = new GoogleMapsDto.SearchTextRequest(
				buildQuery(placeName, placeAddr), properties.languageCode(), properties.regionCode());
		LocalDateTime calledAt = LocalDateTime.now();
		GoogleMapsDto.SearchTextResponse response;
		try {
			// 전송 오류는 여기서 잡지 않고 전파한다 — enricher가 해당 장소만 스킵하고 다음 주기에 재시도한다.
			response = googleMapsApi.searchText(properties.apiKey(), FIELD_MASK, request);
		} catch (RuntimeException e) {
			// 실패해도 과금은 이미 일어났을 수 있다 — 그래서 실패도 한 행으로 남긴다(시도 = 비용).
			record(placeAddr, ExternalApiOutcome.FAILED, calledAt);
			throw e;
		}
		Optional<PlaceHoursFetch> data = response == null
				? Optional.empty()
				: response.firstPlace().map(GoogleMapsDto.Place::toFetch);
		// 검색 결과 없음은 실패가 아니라 "구글이 그런 장소를 모른다"는 사실이다.
		record(placeAddr, data.isPresent() ? ExternalApiOutcome.SUCCESS : ExternalApiOutcome.NO_DATA, calledAt);
		return data;
	}

	@Override
	public PlaceHoursVendor vendor() {
		return PlaceHoursVendor.GOOGLE;
	}

	/** 감사 기록은 부가 기능이다 — 여기서 실패해도 영업시간 보강을 깨지 않는다. */
	private void record(String placeAddr, ExternalApiOutcome outcome, LocalDateTime calledAt) {
		try {
			externalApiCallRepository.save(ExternalApiCallLog.of(ExternalApi.GOOGLE,
					PlaceKey.of(placeAddr), outcome, calledAt));
		} catch (RuntimeException e) {
			log.warn("구글 호출 감사 기록 실패(무시): {}", e.getMessage());
		}
	}

	/** 장소명이 있으면 주소와 함께 질의(매칭 정확도↑). 없으면 주소만. */
	private String buildQuery(String placeName, String placeAddr) {
		if (placeName == null || placeName.isBlank()) {
			return placeAddr;
		}
		return (placeName.trim() + " " + placeAddr).trim();
	}
}
