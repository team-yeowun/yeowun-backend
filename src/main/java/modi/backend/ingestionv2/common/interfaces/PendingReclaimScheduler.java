package modi.backend.ingestionv2.common.interfaces;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.queue.PendingReclaimer;

/** 미처리 항목 회수 - 없으면 컨슈머와 함께 사라진 작업이 되살아나지 않는다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
public class PendingReclaimScheduler {

	private final PendingReclaimer pendingReclaimer;

	@Scheduled(fixedDelayString = "${app.ingestion.v2.reclaim-interval-ms:30000}")
	public void reclaim() {
		pendingReclaimer.reclaimAll();
	}
}
