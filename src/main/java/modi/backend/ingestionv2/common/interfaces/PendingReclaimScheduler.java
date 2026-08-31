package modi.backend.ingestionv2.common.interfaces;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.lock.IngestionJobLock;
import modi.backend.ingestionv2.common.queue.PendingReclaimer;

/**
 * 미처리 항목 회수 - 없으면 컨슈머와 함께 사라진 작업이 되살아나지 않는다.
 *
 * <ul>
 *   <li>전체 미처리 목록을 훑는 잡이라 인스턴스 한 대만 돈다 - XCLAIM 자체는 원자지만
 *       두 대가 같은 목록을 두 번 읽고 두 번 재전달하는 것은 그대로 낭비</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
public class PendingReclaimScheduler {

	private static final String RECLAIM_JOB = "stream-reclaim";

	private final PendingReclaimer pendingReclaimer;
	private final IngestionJobLock jobLock;

	@Scheduled(fixedDelayString = "${app.ingestion.v2.reclaim-interval-ms:30000}")
	public void reclaim() {
		jobLock.runIfAcquired(RECLAIM_JOB, pendingReclaimer::reclaimAll);
	}
}
