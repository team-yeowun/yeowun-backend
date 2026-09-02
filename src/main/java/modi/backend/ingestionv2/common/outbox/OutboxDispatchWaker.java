package modi.backend.ingestionv2.common.outbox;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.interfaces.OutboxDispatchScheduler;

/**
 * 커밋 직후 발송 깨우기 - 폴백 틱을 길게 잡아도 정상 경로의 지연이 ms 에 머무는 이유.
 *
 * <ul>
 *   <li>AFTER_COMMIT 에서만 듣는다 - 롤백된 적재는 깨우지 않는다</li>
 *   <li>코얼레싱 실행기(스레드 1 · 대기 1 · 초과 폐기) - 한 트랜잭션의 적재 N 건이 N 번의 발송이 되지 않는다.
 *       대기열에 이미 하나가 있으면 지금 들어온 요청은 그 실행이 집을 것이므로 버려도 잃는 것이 없다</li>
 *   <li>리더 락을 타지 않는다 - 자기 행은 자기가 곧바로 내보낸다. 두 인스턴스가 동시에 커밋하면 둘이 같은 머리 행을
 *       읽을 수 있지만 행 마커가 중복을 막고, 그 낭비는 동시 커밋 순간에만 한정된다(05 설계 S3-A)</li>
 *   <li>enabled · auto-delivery · dispatch-wake-enabled 셋에 걸린다 - 통합테스트의 auto-delivery=false 무대에서
 *       비동기 발송이 끼어들면 "적재 직후 PENDING" 같은 단언이 깨진다</li>
 *   <li>깨우기 실패는 삼킨다 - 행은 PENDING 으로 남아 폴백 틱이 집는다. 여기서 던지면 실행기 스레드만 죽는다</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ingestion.v2",
		name = {"enabled", "auto-delivery", "dispatch-wake-enabled"}, havingValue = "true")
public class OutboxDispatchWaker {

	private final OutboxDispatcher outboxDispatcher;
	private final MeterRegistry meterRegistry;
	private final ThreadPoolExecutor executor;

	public OutboxDispatchWaker(OutboxDispatcher outboxDispatcher, MeterRegistry meterRegistry) {
		this.outboxDispatcher = outboxDispatcher;
		this.meterRegistry = meterRegistry;
		this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(1), runnable -> {
					Thread thread = new Thread(runnable, "ingestion-dispatch-wake");
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.DiscardPolicy());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAppended(OutboxAppended appended) {
		executor.execute(() -> {
			try {
				Counter.builder(OutboxDispatchScheduler.TRIGGER_COUNTER).tag("source", "wake")
						.register(meterRegistry).increment();
				int published = outboxDispatcher.dispatchPending();
				log.debug("커밋 직후 발송을 깨웠습니다. type={} published={}", appended.type(), published);
			} catch (RuntimeException failure) {
				log.warn("커밋 직후 발송에 실패했습니다. 폴백 틱이 다시 집습니다. type={}", appended.type(), failure);
			}
		});
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}
}
