package modi.backend.ingestionv2.enrich.domain;

/**
 * 스텝 하나의 진행.
 *
 * <ul>
 *   <li>PENDING: 선행 조건이 아직 충족되지 않아 실행할 수 없는 상태</li>
 *   <li>READY: 선행 조건이 충족되어 실행을 기다리는 상태</li>
 *   <li>DONE: 원장 기록까지 끝난 상태</li>
 *   <li>FAILED: 재시도 상한 소진 또는 재시도로 달라지지 않는 실패</li>
 * </ul>
 */
public enum StepStatus {

	PENDING,
	READY,
	DONE,
	FAILED
}
