package modi.backend.application.exhibition.contract;

import modi.backend.domain.exhibition.catalog.ExhibitionRegion;

/**
 * 전시장 등록 계약 — 전시장 축(PLACE_STAGED 소비)이 승격 <b>전에</b> 전시장을 세우는 유일한 통로(설계 §6-3).
 * 코어 리포 직주입 금지 규칙(ADR-12)에 따른 계약이다. 어휘는 코어 소유(원시값·코어 enum)만 쓴다.
 *
 * <p>멱등: 같은 정규화 이름({@code place_key})이면 기존 전시장을 재사용한다 — 승격의 resolve-or-create와
 * 경합해도 자연키가 가드한다. {@code created}가 참일 때만 구글 최초 조회 대상이다(장소당 1콜 원칙).
 */
public interface PlaceRegistrar {

	/** resolve-or-create(멱등). 신규 생성 여부를 함께 돌려준다 — 신규만 영업시간 최초 조회 대상. */
	Resolved resolveOrCreate(String placeName, ExhibitionRegion region, String sigungu, Double gpsX, Double gpsY);

	/** 해소 결과 — 전시장 id·자연키·신규 여부. */
	record Resolved(long exhibitionPlaceId, String placeKey, boolean created) {
	}
}
