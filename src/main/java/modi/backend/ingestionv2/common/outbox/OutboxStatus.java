package modi.backend.ingestionv2.common.outbox;

/**
 * 아웃박스 행의 발행 상태.
 *
 * <ul>
 *   <li>PENDING: 도메인 트랜잭션과 함께 기록되었고 아직 발행하지 않음</li>
 *   <li>SENT: 대기열 발행 완료. 이후 처리 확인은 스트림 미처리 목록이 맡고, 보존 기간이 지나면 정리 대상</li>
 *   <li>FAILED: 발행 재시도 상한을 넘김. 관리자가 확인하고 수동으로 다시 PENDING으로 돌린다</li>
 * </ul>
 */
public enum OutboxStatus {
	PENDING,
	SENT,
	FAILED
}
