package modi.backend.domain.audit;

/**
 * 외부 API 종류({@code external_api_call_log.api}).
 * 벤더가 늘어도 이 값만 늘고 테이블·스키마는 그대로다 — 감사는 처음부터 벤더·모델 불문으로 설계됐다(ERD 3장).
 *
 * <p>수집 슬라이스 소유였다가 공용 감사로 승격(설계 D3)하면서 코어로 왔다 — record·remind의 AI 호출도
 * 같은 테이블에 남기기 위해서다(어느 기능이 불렀는지는 {@link ApiCallSource}가 가른다).
 */
public enum ExternalApi {

	/** 한눈에보는문화정보 realm2(목록) — 무료. */
	CULTURE_LIST,

	/** 한눈에보는문화정보 detail2(상세) — 무료. */
	CULTURE_DETAIL,

	/** Gemini(장르 분류 1차) — 무료 한도 내(실패 시 OpenAI 폴백). */
	GEMINI,

	/** OpenAI(장르 분류 2차 폴백). */
	OPENAI,

	/** Claude(감상문·리마인드 AI). */
	CLAUDE,

	/** 구글 Places(New) Text Search — <b>유료</b>. */
	GOOGLE
}
