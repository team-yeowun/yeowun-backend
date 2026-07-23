package modi.backend.ingestion.domain.draft;

/**
 * draft의 <b>다음 스텝</b> — 저장 상태가 아니라 해소 마커(상세 해소 시각·장르 유무·게이트)에서 파생된다
 * ({@link ExhibitionDraft#nextStep()}). 흐름을 한 눈에 읽기 위한 어휘다:
 * FETCH_DETAIL → CLASSIFY_GENRE → PROMOTE → NONE.
 */
public enum DraftStep {

	/** 상세 스텝 미해소 — 다음 할 일은 상세 조회다. */
	FETCH_DETAIL,

	/** 상세는 해소, 장르 미분류 — 다음 할 일은 AI 분류다. */
	CLASSIFY_GENRE,

	/** 게이트 충족(목록 코어+상세 해소+장르) — 다음 할 일은 승격(DRAFT_READY 소비)이다. */
	PROMOTE,

	/** 할 일 없음 — 종료됐거나(COMPLETED/FAILED) 목록 코어가 아직 불완전하다. */
	NONE
}
