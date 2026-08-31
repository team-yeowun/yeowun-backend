package modi.backend.ingestionv2.common.interfaces;

import java.time.LocalDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.lock.IngestionJobLock;
import modi.backend.ingestionv2.common.outbox.OutboxDispatchOutcome;
import modi.backend.ingestionv2.common.outbox.OutboxDispatcher;
import modi.backend.ingestionv2.common.outbox.OutboxService;

/**
 * 아웃박스 발송의 심장박동.
 *
 * <ul>
 *   <li>발송 주기는 짧게 - 파이프라인 지연이 곧 이 주기의 누적</li>
 *   <li>한 틱은 배치를 소진할 때까지 반복 - 주기를 늘리는 대신 한 번 깨어났을 때 적체를 비운다</li>
 *   <li>소진 루프는 트랜잭션 밖 - 배치 하나가 트랜잭션 하나다. 루프를 트랜잭션 안으로 넣으면
 *       한 틱이 잡은 행 잠금이 틱 전체 동안 쌓여 배치를 나눈 의미가 사라진다</li>
 *   <li>루프 종료 조건은 "집을 것이 없거나 하나도 맡지 못했을 때" - 다른 인스턴스가 맡은 행을 붙잡고
 *       같은 틱에서 다시 읽지 않는다</li>
 *   <li>발행 실패 재시도는 같은 틱 - PENDING으로 남은 행을 다음 틱이 다시 집는다</li>
 *   <li>정리는 하루 한 번 소량 - 대량 단발 삭제 금지. 전체를 훑는 잡이라 인스턴스 한 대만 돈다</li>
 *   <li>발송에는 잡 락을 걸지 않는다 - 행 단위 판정이 이미 중복을 막고, 잡 락을 걸면 한 대만 일하게 되어
 *       인스턴스를 늘린 만큼 처리량이 늘지 않는다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
public class OutboxDispatchScheduler {

	/** 틱 하나의 소요 시간 - 소진 루프 전체를 포함한다. */
	public static final String TICK_TIMER = "ingestion.outbox.dispatch.tick";

	private static final String CLEANUP_JOB = "outbox-cleanup";

	private final OutboxDispatcher outboxDispatcher;
	private final OutboxService outboxService;
	private final IngestionJobLock jobLock;
	private final IngestionProperties properties;
	private final MeterRegistry meterRegistry;

	@Scheduled(fixedDelayString = "${app.ingestion.v2.dispatch-interval-ms:1000}")
	public void dispatch() {
		Timer.Sample sample = Timer.start(meterRegistry);
		int batches = 0;
		int published = 0;
		OutboxDispatchOutcome outcome;
		do {
			outcome = outboxDispatcher.dispatchBatch();
			published += outcome.published();
			batches++;
		} while (properties.dispatchDrain() && outcome.drainable()
				&& batches < properties.dispatchDrainMaxBatches());
		sample.stop(Timer.builder(TICK_TIMER).publishPercentileHistogram().register(meterRegistry));
		if (published > 0) {
			log.debug("아웃박스를 발송했습니다. count={} batches={}", published, batches);
		}
	}

	@Scheduled(cron = "${app.ingestion.v2.cleanup-cron:0 0 3 * * *}")
	public void cleanup() {
		jobLock.runIfAcquired(CLEANUP_JOB, this::cleanupSent);
	}

	private void cleanupSent() {
		LocalDateTime threshold = IngestionClock.now().minusDays(properties.retentionDays());
		int deleted = outboxService.cleanupSent(threshold, properties.cleanupBatchSize());
		if (deleted > 0) {
			log.info("발행 완료 기록을 정리했습니다. deleted={} threshold={}", deleted, threshold);
		}
	}
}
