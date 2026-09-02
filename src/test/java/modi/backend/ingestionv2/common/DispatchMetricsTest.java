package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxDispatcher;

/** 발송 단계의 낭비와 지연을 계기로 셀 수 있어야 실험이 개선율을 말할 수 있다(05). */
@DisplayName("발송 계기")
class DispatchMetricsTest extends DeliveryTestSupport {

	@Autowired private MeterRegistry meterRegistry;

	@Test
	@DisplayName("집을 것이 없는 선점은 rows=empty 로, 있으면 nonempty 로 센다")
	void 빈_선점과_비빈_선점을_가른다() {
		// given
		double emptyBefore = claims("empty");
		double nonemptyBefore = claims("nonempty");

		// when 빈 아웃박스를 한 번 훑고, 한 행을 넣고 다시 훑는다
		outboxDispatcher.dispatchPending();
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		outboxDispatcher.dispatchPending();

		// then
		assertThat(claims("empty") - emptyBefore).isEqualTo(1);
		assertThat(claims("nonempty") - nonemptyBefore).isEqualTo(1);
	}

	@Test
	@DisplayName("SENT 전이는 적재부터 발송까지의 지연을 한 건 기록한다")
	void 발송_지연을_기록한다() {
		// given
		Timer before = latency();
		long countBefore = before == null ? 0 : before.count();
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		outboxDispatcher.dispatchPending();

		// then
		Timer timer = latency();
		assertThat(timer).isNotNull();
		assertThat(timer.count() - countBefore).isEqualTo(1);
		assertThat(timer.max(TimeUnit.MILLISECONDS)).isLessThan(5_000);
	}

	private double claims(String rows) {
		return meterRegistry.find(OutboxDispatcher.CLAIM_COUNTER).tag("rows", rows).counters().stream()
				.mapToDouble(Counter::count).sum();
	}

	private Timer latency() {
		return meterRegistry.find(OutboxDispatcher.LATENCY_TIMER).tag("event_type", "COLLECTED").timer();
	}
}
