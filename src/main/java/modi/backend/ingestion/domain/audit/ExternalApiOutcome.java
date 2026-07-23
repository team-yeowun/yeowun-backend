package modi.backend.ingestion.domain.audit;

/**
 * 외부 호출의 결말({@code external_api_call.outcome}).
 *
 * <p>성패만이 아니라 <b>"불렀는데 줄 게 없었다"(NO_DATA)</b>와 <b>"한도에 막혔다"(RATE_LIMITED)</b>를 구분한다 —
 * 전자는 원천의 사실이고 후자는 우리 쪽 운영 신호(무료 한도 소진 → 폴백 발생)라 대응이 완전히 다르기 때문이다.
 */
public enum ExternalApiOutcome {

	SUCCESS,

	/** 호출은 정상인데 원천에 줄 데이터가 없었다(빈 응답·검색 결과 없음). */
	NO_DATA,

	/** 한도 초과(429). Gemini 무료 한도 소진 시 랜덤 폴백이 도는 지점이라, 이 값의 추이가 곧 폴백 비율이다. */
	RATE_LIMITED,

	/** 전송 오류·비정상 응답. */
	FAILED
}
