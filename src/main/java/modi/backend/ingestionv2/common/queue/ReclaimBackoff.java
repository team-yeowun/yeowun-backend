package modi.backend.ingestionv2.common.queue;

/**
 * 미처리 항목을 다시 집기까지 기다리는 방식.
 *
 * <ul>
 *   <li>NONE: 전달 횟수와 무관하게 같은 시간만 기다린다 - 실패가 계속되면 같은 간격으로 계속 다시 온다</li>
 *   <li>EXPONENTIAL: 전달 횟수만큼 기다리는 시간이 배로 늘고 상한에서 멈춘다 - 낫지 않는 실패에 부하를 덜 준다</li>
 * </ul>
 */
public enum ReclaimBackoff {
	NONE,
	EXPONENTIAL
}
