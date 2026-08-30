package modi.backend.ingestionv2.common.interfaces;

import java.time.LocalDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.outbox.OutboxDispatcher;
import modi.backend.ingestionv2.common.outbox.OutboxService;

/**
 * 아웃박스 발송의 심장박동.
 *
 * <ul>
 *   <li>발송 주기는 짧게 - 파이프라인 지연이 곧 이 주기의 누적</li>
 *   <li>발행 실패 재시도는 같은 틱 - PENDING으로 남은 행을 다음 틱이 다시 집는다</li>
 *   <li>정리는 하루 한 번 소량 - 대량 단발 삭제 금지</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
public class OutboxDispatchScheduler {

	private final OutboxDispatcher outboxDispatcher;
	private final OutboxService outboxService;
	private final IngestionProperties properties;

	@Scheduled(fixedDelayString = "${app.ingestion.v2.dispatch-interval-ms:1000}")
	public void dispatch() {
		int sent = outboxDispatcher.dispatchPending();
		if (sent > 0) {
			log.debug("아웃박스를 발송했습니다. count={}", sent);
		}
	}

	@Scheduled(cron = "${app.ingestion.v2.cleanup-cron:0 0 3 * * *}")
	public void cleanup() {
		LocalDateTime threshold = IngestionClock.now().minusDays(properties.retentionDays());
		int deleted = outboxService.cleanupSent(threshold, properties.cleanupBatchSize());
		if (deleted > 0) {
			log.info("발행 완료 기록을 정리했습니다. deleted={} threshold={}", deleted, threshold);
		}
	}
}
