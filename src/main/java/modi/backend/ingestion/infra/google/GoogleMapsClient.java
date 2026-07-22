package modi.backend.ingestion.infra.google;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;
import modi.backend.ingestion.domain.data.PlaceHoursFetch;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.properties.GoogleMapsProperties;

/**
 * 구글 Places(New) 실호출 영업시간 조회기. 장소명+주소로 Text Search({@code /v1/places:searchText}) 1콜을 보내
 * {@code regularOpeningHours}를 받는다. 요청선 조립은 {@link RestClient}로 직접 한다(공공데이터 전시 API와 같은 구조).
 * <p>
 * 계약({@link PlaceHoursProvider}): 미발견은 {@link Optional#empty()}, 전송 오류(RestClient 예외)는 전파해 상위가 스킵/재시도한다.
 * 장소는 찾았으나 영업시간이 없으면 {@link WeeklyOpeningHours#empty()}를 담아 반환한다(장소 확인은 됐으므로 재조회 대상에서 빠지게).
 * 운영에서만 선택되며(mock 기본), 키 미설정 시엔 애초에 {@code MockPlaceHoursProvider}가 @Primary로 선택된다.
 * <p>
 * <b>파싱도 감사도 여기 없다</b> — 구글 어휘(0=일요일 day 인덱스·hour/minute)를 도메인 값으로 옮기는 일은 응답 record
 * (진입점 {@link GoogleMapsDto.SearchTextResponse#toPlaceHours()})가, 호출 감사는 호출부({@code PlaceHoursReader})가 맡는다. 이 클래스는
 * <b>보내고 받는 데까지</b>이고, 리포지토리가 하나도 주입되지 않는다(culture의 {@code CultureExhibitionClient}와 같은 모양).
 */
@Component
@RequiredArgsConstructor
public class GoogleMapsClient implements PlaceHoursProvider {

    /** New API 필수 헤더 — 받을 필드만. 영업시간까지 한 콜로 받는다(2단계 Place Details 불필요). */
    private static final String FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.regularOpeningHours";

    /** 필드명이 곧 빈 이름이다 — RestClient 빈이 여럿이라 이름으로 해소된다(@Qualifier 대체). */
    private final RestClient googleMapsRestClient;
    private final GoogleMapsProperties properties;

    @Override
    public Optional<PlaceHoursFetch> fetch(String placeName, String placeAddr) {
        GoogleMapsDto.SearchTextRequest request = new GoogleMapsDto.SearchTextRequest(
                buildQuery(placeName, placeAddr), properties.languageCode(), properties.regionCode());
        // 전송 오류는 잡지 않고 전파한다 — 호출부가 해당 장소만 스킵하고 다음 주기에 재시도한다.
        GoogleMapsDto.SearchTextResponse response = googleMapsRestClient.post()
                .uri("/v1/places:searchText")
                // 인증키는 URL 노출을 피해 헤더로. FieldMask는 New API 필수 — 없으면 400.
                .header("X-Goog-Api-Key", properties.apiKey())
                .header("X-Goog-FieldMask", FIELD_MASK)
                .body(request)
                .retrieve()
                .body(GoogleMapsDto.SearchTextResponse.class);
        // 응답 자체가 없는 경우와 응답은 왔으나 후보가 없는 경우를 SearchTextResponse.empty()로 합류시켜
        // 파이프라인 진입점(toPlaceHours) 하나로 처리한다(결측 분기 제거).
        return Optional.ofNullable(response)
                .orElseGet(GoogleMapsDto.SearchTextResponse::empty)
                .toPlaceHours();
    }

    @Override
    public PlaceHoursVendor vendor() {
        return PlaceHoursVendor.GOOGLE;
    }

    /** 장소명이 있으면 주소와 함께 질의(매칭 정확도↑). 없으면 주소만. */
    private String buildQuery(String placeName, String placeAddr) {
        if (placeName == null || placeName.isBlank()) {
            return placeAddr;
        }
        return (placeName.trim() + " " + placeAddr).trim();
    }
}
