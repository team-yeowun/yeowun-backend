package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.interfaces.OutboxDispatchScheduler;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;

/**
 * 커밋 직후 깨우기 - 폴백 틱을 60초로 늘려도 발송 지연이 ms 단위로 남는 근거(05).
 *
 * <p>비동기 배달을 켠 별도 컨텍스트다. 컨슈머는 끄고(외부 경로 차단) 폴백 틱·회수·트리밍은 실험 창 밖으로 민다 -
 * 이 테스트가 보는 SENT 전이는 깨우기 경로 하나여야 한다.
 */
@SpringBootTest(properties = {
		"app.ingestion.v2.enabled=true",
		"app.ingestion.v2.auto-delivery=true",
		"app.ingestion.v2.consume-enabled=false",
		"app.ingestion.v2.dispatch-interval-ms=600000",
		"app.ingestion.v2.reclaim-interval-ms=600000",
		"app.ingestion.v2.trim-cron=-",
		"app.ingestion.v2.cleanup-cron=-",
		"app.exhibition.enrich.scheduling-enabled=false"
})
@DisplayName("커밋 직후 발송 깨우기")
class DispatchWakeTest extends IngestionTestSupport {

	@Autowired private MeterRegistry meterRegistry;

	@Test
	@DisplayName("적재 트랜잭션이 커밋되면 스케줄러 없이도 1초 안에 SENT 가 된다")
	void 커밋되면_곧바로_발송된다() {
		// given
		double wakesBefore = wakes();

		// when
		LocalDateTime committedAt = transactionTemplate.execute(status -> {
			outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
			return IngestionClock.now();
		});

		// then
		Outbox sent = Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
				.until(() -> outboxRepository.findAll().getFirst(), o -> o.getStatus() == OutboxStatus.SENT);
		long latencyMs = Duration.between(committedAt, sent.getSentAt()).toMillis();
		System.out.printf("[05] 커밋 → SENT 지연 = %d ms%n", latencyMs);
		assertThat(latencyMs).isLessThan(1_000);
		assertThat(wakes() - wakesBefore).isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("롤백된 적재는 깨우지 않는다")
	void 롤백은_깨우지_않는다() {
		// given
		double wakesBefore = wakes();

		// when
		try {
			transactionTemplate.execute(status -> {
				outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
				throw new IllegalStateException("도메인 실패");
			});
		} catch (IllegalStateException expected) {
			// 의도한 실패
		}
		sleep(500);

		// then
		assertThat(outboxRepository.count()).isZero();
		assertThat(wakes() - wakesBefore).isZero();
	}

	@Test
	@DisplayName("한 트랜잭션의 적재 여러 건은 한두 번의 깨우기로 모두 발송된다")
	void 여러_건은_코얼레싱된다() {
		// given
		double wakesBefore = wakes();

		// when
		transactionTemplate.executeWithoutResult(status -> {
			for (int i = 0; i < 5; i++) {
				outboxAppender.append(IngestionEventType.COLLECTED, vendorKey + "-" + i);
			}
		});

		// then 다섯 행이 다 SENT 이고 깨우기 실행은 두 번을 넘지 않는다(스레드 1 · 대기 1)
		Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
				.until(() -> outboxRepository.count() == 5
						&& outboxRepository.findAll().stream().allMatch(o -> o.getStatus() == OutboxStatus.SENT));
		assertThat(wakes() - wakesBefore).isBetween(1.0, 2.0);
	}

	private double wakes() {
		Counter counter = meterRegistry.find(OutboxDispatchScheduler.TRIGGER_COUNTER).tag("source", "wake").counter();
		return counter == null ? 0 : counter.count();
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
