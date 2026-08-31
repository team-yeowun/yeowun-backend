package modi.backend.ingestionv2.common.outbox;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.queue.EventDispatcher;
import modi.backend.ingestionv2.common.queue.IngestionStream;

/**
 * 발송 단계 - 아웃박스와 대기열을 잇는 유일한 지점.
 *
 * <ul>
 *   <li>한 트랜잭션에 선점과 발행과 표시만 - 외부 API 호출은 들어오지 않음</li>
 *   <li>행 하나의 발행 실패가 배치를 되돌리지 않음 - 그 행만 시도 횟수를 올리고 나머지는 계속 나간다</li>
 *   <li>배정 스트림이 없는 이벤트는 발행 없이 곧바로 종결(STAGED)</li>
 *   <li>발행 카운터의 표본은 스트림 배정이 있는 행만 - 즉시 종결 경로를 섞으면 발행 시도 총량이 부풀어
 *       행의 retry_count 합계와 맞지 않음</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

	/** 발행 시도 결과. 스트림 배정이 없는 즉시 종결 행은 세지 않는다. */
	public static final String PUBLISH_COUNTER = "ingestion.outbox.publish";

	/** 발행 재시도 상한을 넘겨 걷어낸 행. */
	public static final String FAILED_COUNTER = "ingestion.outbox.failed";

	private final OutboxService outboxService;
	private final EventDispatcher eventDispatcher;
	private final IngestionProperties properties;
	private final MeterRegistry meterRegistry;

	/** 발송 한 틱 - 미발행 행을 배치 크기만큼 선점해 내보낸다. 돌려주는 값은 선점한 행 수. */
	@Transactional
	public int dispatchPending() {
		List<Outbox> claimed = outboxService.claimPending(properties.dispatchBatchSize());
		claimed.forEach(this::send);
		return claimed.size();
	}

	private void send(Outbox outbox) {
		if (IngestionStream.of(outbox.getEventType()).isEmpty()) {
			// 소비자가 없는 종결 사실은 스트림을 거치지 않는다. 여기서 닫지 않으면 PENDING인 채로 매 틱 다시 선점된다.
			outboxService.markSentWithoutStream(outbox, IngestionClock.now());
			return;
		}
		try {
			eventDispatcher.dispatch(outbox.toPayload());
			outboxService.markSent(outbox, IngestionClock.now());
			publishCounter("success").increment();
		} catch (RuntimeException failure) {
			outboxService.markPublishFailed(outbox, properties.maxAttempts());
			publishCounter("failure").increment();
			if (outbox.isFailed()) {
				Counter.builder(FAILED_COUNTER).register(meterRegistry).increment();
				log.error("발행 재시도 상한을 넘겨 실패로 걷어냅니다. id={} type={} aggregateId={} retryCount={}",
						outbox.getId(), outbox.getEventType(), outbox.getAggregateId(), outbox.getRetryCount(), failure);
			} else {
				log.warn("발행에 실패해 다음 틱에 다시 시도합니다. id={} type={} aggregateId={} retryCount={}",
						outbox.getId(), outbox.getEventType(), outbox.getAggregateId(), outbox.getRetryCount(), failure);
			}
		}
	}

	private Counter publishCounter(String result) {
		return Counter.builder(PUBLISH_COUNTER).tag("result", result).register(meterRegistry);
	}
}
