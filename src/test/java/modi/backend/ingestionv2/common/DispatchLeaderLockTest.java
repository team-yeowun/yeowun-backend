package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.interfaces.OutboxDispatchScheduler;
import modi.backend.ingestionv2.common.lock.IngestionJobLock;
import modi.backend.ingestionv2.common.lock.RedisMarkerLock;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;

/** 발송 폴백 틱은 리더 한 대만 돈다 - 두 대가 같은 머리 행을 읽고 버리는 낭비의 근원을 없앤다(05). */
@DisplayName("발송 리더 락")
class DispatchLeaderLockTest extends DeliveryTestSupport {

	@Autowired private IngestionJobLock jobLock;
	@Autowired private RedisMarkerLock markerLock;
	@Autowired private MeterRegistry meterRegistry;

	/** 스케줄러 빈은 auto-delivery=false 에서 등록되지 않으므로 같은 의존성으로 직접 만든다. */
	private OutboxDispatchScheduler scheduler() {
		return new OutboxDispatchScheduler(outboxDispatcher, outboxService, jobLock, properties, meterRegistry);
	}

	@Test
	@DisplayName("다른 인스턴스가 발송 락을 쥐고 있으면 이 틱은 선점 조회를 한 번도 하지 않는다")
	void 락을_못_잡은_틱은_조회하지_않는다() {
		// given 다른 인스턴스가 리더다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		assertThat(markerLock.tryAcquire("lock:" + OutboxDispatchScheduler.DISPATCH_JOB, "other-app",
				Duration.ofSeconds(30))).isTrue();
		double claimsBefore = claimCount();
		double skippedBefore = triggerCount("skipped");

		// when
		scheduler().dispatch();

		// then 행은 그대로 PENDING 이고 조회도 없었고 건너뜀이 한 번 기록된다
		assertThat(outboxRepository.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(claimCount()).isEqualTo(claimsBefore);
		assertThat(triggerCount("skipped") - skippedBefore).isEqualTo(1);
	}

	@Test
	@DisplayName("락을 잡은 틱은 발송하고 끝나면 락을 돌려준다")
	void 락을_잡은_틱은_발송하고_락을_돌려준다() {
		// given
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		double scheduleBefore = triggerCount("schedule");

		// when
		scheduler().dispatch();

		// then
		assertThat(outboxRepository.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(triggerCount("schedule") - scheduleBefore).isEqualTo(1);
		assertThat(markerLock.tryAcquire("lock:" + OutboxDispatchScheduler.DISPATCH_JOB, "next-app",
				Duration.ofSeconds(1))).as("틱이 끝나면 락 키가 사라져 다음 인스턴스가 잡을 수 있다").isTrue();
	}

	private double claimCount() {
		return meterRegistry.find("ingestion.outbox.claim").counters().stream()
				.mapToDouble(Counter::count).sum();
	}

	private double triggerCount(String source) {
		Counter counter = meterRegistry.find(OutboxDispatchScheduler.TRIGGER_COUNTER).tag("source", source).counter();
		return counter == null ? 0 : counter.count();
	}
}
