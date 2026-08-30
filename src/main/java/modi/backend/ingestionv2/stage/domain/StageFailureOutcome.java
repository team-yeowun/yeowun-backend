package modi.backend.ingestionv2.stage.domain;

/**
 * 실패 기록의 결과.
 *
 * <ul>
 *   <li>RETRYABLE: 시도 횟수를 늘렸고 상한이 남아 있음, 호출자는 원래 예외를 다시 던짐</li>
 *   <li>EXHAUSTED: 상한을 소진해 FAILED 로 전이, 호출자는 격리를 요청</li>
 *   <li>ALREADY_SETTLED: 이미 STAGED 라 아무것도 기록하지 않음, 호출자는 정상 반환</li>
 * </ul>
 */
public enum StageFailureOutcome {

	RETRYABLE,
	EXHAUSTED,
	ALREADY_SETTLED
}
