package modi.backend.ingestion.domain.audit;

/**
 * 외부 API 종류({@code external_api_call.api}).
 * 벤더가 늘어도 이 값만 늘고 테이블·스키마는 그대로다 — 감사는 처음부터 벤더·모델 불문으로 설계됐다(ERD 3장).
 */
public enum ExternalApi {

	/** 한눈에보는문화정보 realm2(목록) — 무료. */
	CULTURE_LIST,

	/** 한눈에보는문화정보 detail2(상세) — 무료. */
	CULTURE_DETAIL,

	/** Gemini 장르 분류 — 현재 무료 한도 내(429 시 랜덤 폴백). */
	GEMINI,

	/** 아직 도입 전 — 감상문 AI가 Gemini → Claude로 전환한 이력이 있어 장르도 올 수 있다(값만 늘면 된다). */
	CLAUDE,

	/** 구글 Places(New) Text Search — <b>유료</b>. */
	GOOGLE
}
