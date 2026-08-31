package modi.backend.ingestionv2.enrich.domain.hours;

/** 개장 시간 조회 입력. 상세 원장에서 뽑은 장소명과 주소. */
public record PlaceInput(String placeName, String placeAddress) {
}
