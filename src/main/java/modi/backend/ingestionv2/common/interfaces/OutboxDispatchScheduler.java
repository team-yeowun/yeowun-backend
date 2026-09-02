package modi.backend.ingestionv2.common.interfaces;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.inbox.InboxService;
import modi.backend.ingestionv2.common.lock.IngestionJobLock;
import modi.backend.ingestionv2.common.outbox.OutboxDispatchOutcome;
import modi.backend.ingestionv2.common.outbox.OutboxDispatcher;
import modi.backend.ingestionv2.common.outbox.OutboxService;

/**
 * 아웃박스 발송의 심장박동.
 *
 * <ul>
 *   <li>폴백 주기는 길게(60초) - 정상 경로는 커밋 직후 깨우기(OutboxDispatchWaker)가 맡는다. 1초 폴링은 유휴 상태에서
 *       하루 172,800회(2대) DB 를 두드렸고 그중 대부분이 빈 조회였다(05 실험)</li>
 *   <li>한 틱은 배치를 소진할 때까지 반복 - 주기를 늘리는 대신 한 번 깨어났을 때 적체를 비운다</li>
 *   <li>소진 루프는 트랜잭션 밖 - 배치 하나가 트랜잭션 하나다. 루프를 트랜잭션 안으로 넣으면
 *       한 틱이 잡은 행 잠금이 틱 전체 동안 쌓여 배치를 나눈 의미가 사라진다</li>
 *   <li>루프 종료 조건은 "집을 것이 없거나 하나도 맡지 못했을 때" - 다른 인스턴스가 맡은 행을 붙잡고
 *       같은 틱에서 다시 읽지 않는다</li>
 *   <li>발행 실패 재시도는 같은 틱 - PENDING으로 남은 행을 다음 틱이 다시 집는다</li>
 *   <li>정리는 하루 한 번 소량 - 대량 단발 삭제 금지. 전체를 훑는 잡이라 인스턴스 한 대만 돈다</li>
 *   <li>폴백 틱은 리더 한 대만 돈다 - 두 대가 같은 머리 행을 읽고 한쪽이 버리는 낭비(push-2 런 27,000행)는 행 마커로
 *       막히지 않는다. 소비 병렬성은 컨슈머 그룹이 맡으므로 발송을 여러 대가 할 이유가 없다(1대 실측 1,869행/s).
 *       락 TTL 은 짧게 따로 둔다 - 강제 종료 때의 정지 시간이 곧 이 값이다. 스위치를 끄면 예전처럼 락 없이 돈다(부하 실험 before)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
public class OutboxDispatchScheduler {

	/** 틱 하나의 소요 시간 - 소진 루프 전체를 포함한다. */
	public static final String TICK_TIMER = "ingestion.outbox.dispatch.tick";

	/** 발송이 왜 돌았나. schedule = 폴백 틱, skipped = 다른 리더가 맡아 건너뜀, wake = 커밋 직후 깨우기(OutboxDispatchWaker). */
	public static final String TRIGGER_COUNTER = "ingestion.outbox.dispatch.trigger";

	/** 발송 리더 락의 잡 이름 - 키는 lock:outbox-dispatch. */
	public static final String DISPATCH_JOB = "outbox-dispatch";

	private static final String CLEANUP_JOB = "outbox-cleanup";

	private final OutboxDispatcher outboxDispatcher;
	private final OutboxService outboxService;
	private final InboxService inboxService;
	private final IngestionJobLock jobLock;
	private final IngestionProperties properties;
	private final MeterRegistry meterRegistry;

	@Scheduled(fixedDelayString = "${app.ingestion.v2.dispatch-interval-ms:60000}")
	public void dispatch() {
		if (!properties.dispatchLeaderLock()) {
			trigger("schedule").increment();
			drain();
			return;
		}
		boolean ran = jobLock.runIfAcquired(DISPATCH_JOB, Duration.ofMillis(properties.dispatchLockTtlMs()), this::drain);
		trigger(ran ? "schedule" : "skipped").increment();
	}

	private void drain() {
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

	private Counter trigger(String source) {
		return Counter.builder(TRIGGER_COUNTER).tag("source", source).register(meterRegistry);
	}

	@Scheduled(cron = "${app.ingestion.v2.cleanup-cron:0 0 3 * * *}")
	public void cleanup() {
		jobLock.runIfAcquired(CLEANUP_JOB, this::cleanupDeliveryRecords);
	}

	private void cleanupDeliveryRecords() {
		LocalDateTime now = IngestionClock.now();
		LocalDateTime outboxThreshold = now.minusDays(properties.retentionDays());
		int deletedOutbox = outboxService.cleanupSent(outboxThreshold, properties.cleanupBatchSize());
		if (deletedOutbox > 0) {
			log.info("발행 완료 기록을 정리했습니다. deleted={} threshold={}", deletedOutbox, outboxThreshold);
		}

		LocalDateTime inboxThreshold = now.minusDays(properties.inboxRetentionDays());
		int deletedInbox = 0;
		for (int batch = 0; batch < properties.inboxCleanupMaxBatches(); batch++) {
			int deleted = inboxService.cleanupTerminal(inboxThreshold, properties.inboxCleanupBatchSize());
			deletedInbox += deleted;
			if (deleted < properties.inboxCleanupBatchSize()) {
				break;
			}
		}
		if (deletedInbox > 0) {
			log.info("Inbox 종결 기록을 정리했습니다. deleted={} threshold={}", deletedInbox, inboxThreshold);
		}
	}
}
