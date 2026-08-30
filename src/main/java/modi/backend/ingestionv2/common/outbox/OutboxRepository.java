package modi.backend.ingestionv2.common.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 아웃박스 포트 - 스프링 무의존.
 *
 * <ul>
 *   <li>선점은 메서드 하나 - 락 방식(전략)과 조회 대상 DB 는 인자로 받고 분기는 어댑터 안에서만 한다.
 *       전략마다 포트 메서드를 두면 포트가 인프라 어휘(SKIP LOCKED·복제본)를 그대로 안게 된다</li>
 *   <li>조회 셋(선점·실패 목록·정리)이 전부 (status, created_at) 인덱스 하나를 탄다</li>
 *   <li>정리는 소량 배치 삭제 - 대량 단발 삭제 금지</li>
 * </ul>
 */
public interface OutboxRepository {

	Outbox save(Outbox outbox);

	Optional<Outbox> findById(long id);

	/**
	 * 미발행 행을 오래된 순으로 선점한다.
	 *
	 * @param limit 한 번에 집는 행 수. 0 이면 상한 없이 미발행 행 전부
	 */
	List<Outbox> claimPending(int limit, OutboxClaimStrategy strategy, OutboxReadSource readSource);

	/** 발행 실패로 걷어낸 행 - 관리자가 읽는다. */
	List<Outbox> findFailed(int limit);

	/** 미발행 행 수 - 적체 관측의 입력. */
	long countPending();

	/** 정리 대상 - 적재 시각이 보존 기간을 지난 발행 완료 행. */
	List<Outbox> findSentBefore(LocalDateTime threshold, int limit);

	void deleteAll(List<Outbox> outboxes);
}
