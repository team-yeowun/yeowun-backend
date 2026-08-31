package modi.backend.ingestionv2.common.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 아웃박스 포트 - 스프링 무의존.
 *
 * <ul>
 *   <li>claimPending 구현은 FOR UPDATE SKIP LOCKED - 인스턴스 두 대가 같은 행을 집지 않게 함</li>
 *   <li>조회 셋(선점·실패 목록·정리)이 전부 (status, created_at) 인덱스 하나를 탄다</li>
 *   <li>정리는 소량 배치 삭제 - 대량 단발 삭제 금지</li>
 * </ul>
 */
public interface OutboxRepository {

	Outbox save(Outbox outbox);

	Optional<Outbox> findById(long id);

	/** 미발행 행을 오래된 순으로 선점한다. */
	List<Outbox> claimPending(int limit);

	/** 발행 실패로 걷어낸 행 - 관리자가 읽는다. */
	List<Outbox> findFailed(int limit);

	/** 미발행 행 수 - 적체 관측의 입력. */
	long countPending();

	/** 정리 대상 - 적재 시각이 보존 기간을 지난 발행 완료 행. */
	List<Outbox> findSentBefore(LocalDateTime threshold, int limit);

	void deleteAll(List<Outbox> outboxes);
}
