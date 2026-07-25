package modi.backend.ingestion.domain.progress;

/**
 * 전시장 축 resolve-or-create의 분기 결과 — 대시보드의 "전시장 새/기존" 컬럼(설계 §7).
 * PLACE_STAGED 소비가 같은 place_key의 미종료 진행 행에 마크한다.
 */
public enum PlaceOutcome {

	/** 이 수집이 만든 신규 전시장 — 구글 최초 조회 대상이었다. */
	NEW,

	/** 이미 있던 전시장 재사용 — 구글 호출 없음. */
	EXISTING
}
