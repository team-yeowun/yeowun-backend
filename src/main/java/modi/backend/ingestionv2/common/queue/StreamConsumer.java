package modi.backend.ingestionv2.common.queue;

import java.time.Duration;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.deadletter.DeadLetter;
import modi.backend.ingestionv2.common.deadletter.DeadLetterService;
import modi.backend.support.error.CoreException;

/**
 * 소비 어댑터 - 트랜잭션 없이 실행하고 결과에 따라 확인하거나 남긴다.
 *
 * <ul>
 *   <li>처리 대상은 레코드의 payload에서 읽는다 - 아웃박스 테이블을 다시 조회하지 않음</li>
 *   <li>트랜잭션을 열지 않음 - 도메인 핸들러가 자기 경계를 스스로 정하게 함</li>
 *   <li>성공은 확인(ack), 소진은 격리 후 확인, 그 밖의 실패는 미확인으로 남김</li>
 *   <li>격리 판정은 도메인이 보낸 오류 코드 하나로만 - 도메인 클래스를 알지 못함</li>
 *   <li>격리 기록의 난 지점은 핸들러 클래스 이름, 해석 실패는 DECODE</li>
 *   <li>처리 시간과 완료 시간을 두 자로 나눠 잰다 - 앞은 핸들러 한 번의 비용, 뒤는 적재부터 확인까지의 파이프라인 지연</li>
 *   <li>완료 시간의 시작점은 이벤트가 난 시각(payload) - 아웃박스 행의 발행 시각이 아니다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

	/** 처리 한 번의 비용. 태그 result 로 성공·재시도 잔류·소진을 가른다. */
	public static final String CONSUME_TIMER = "ingestion.consume";

	/** 적재(payload 발생 시각)부터 확인(ack)까지 - 파이프라인 전체의 완료 시간. */
	public static final String COMPLETION_TIMER = "ingestion.event.completion";

	private final IngestionEventRouter eventRouter;
	private final DeadLetterService deadLetterService;
	private final StringRedisTemplate redisTemplate;
	private final IngestionProperties properties;
	private final MeterRegistry meterRegistry;

	@Override
	public void onMessage(MapRecord<String, String, String> message) {
		EventRecord event;
		try {
			event = EventRecord.from(message.getValue());
		} catch (CoreException malformed) {
			log.error("배달 레코드를 해석할 수 없어 격리합니다. stream={} recordId={}",
					message.getStream(), message.getId(), malformed);
			deadLetterService.isolateMalformed(message.getStream(), message.getId().getValue(),
					EventRecord.rawPayloadOf(message.getValue()), DeadLetter.Failure.of(malformed, DeadLetter.STEP_DECODE));
			acknowledge(message);
			return;
		}

		IngestionEventHandler handler = eventRouter.route(event.type()).orElse(null);
		if (handler == null) {
			log.info("맡는 곳이 없는 이벤트라 그대로 종결합니다. type={} aggregateId={}", event.type(), event.aggregateId());
			acknowledge(message);
			return;
		}

		execute(handler, event, message);
	}

	private void execute(IngestionEventHandler handler, EventRecord event,
			MapRecord<String, String, String> message) {
		long started = System.nanoTime();
		try {
			handler.handle(event.aggregateId());
			acknowledge(message);
			record(event, "success", started);
			recordCompletion(event);
		} catch (CoreException failure) {
			if (failure.errorCode() == IngestionErrorCode.RETRY_EXHAUSTED) {
				isolate(event, message, DeadLetter.Failure.of(failure, handler.getClass().getSimpleName()));
				record(event, "exhausted", started);
				return;
			}
			record(event, "retry", started);
			log.warn("이벤트 처리에 실패해 미확인으로 남깁니다. type={} aggregateId={}",
					event.type(), event.aggregateId(), failure);
		} catch (RuntimeException failure) {
			record(event, "retry", started);
			log.warn("이벤트 처리 중 예상하지 못한 오류가 발생했습니다. type={} aggregateId={}",
					event.type(), event.aggregateId(), failure);
		}
	}

	private void record(EventRecord event, String result, long startedNanos) {
		Timer.builder(CONSUME_TIMER)
				.tag("event_type", event.type().name())
				.tag("result", result)
				.register(meterRegistry)
				.record(Duration.ofNanos(System.nanoTime() - startedNanos));
	}

	/** 완료 시간은 벽시계 두 지점의 차다 - 재전달로 여러 번 왔더라도 성공한 그 한 번만 기록한다. */
	private void recordCompletion(EventRecord event) {
		Timer.builder(COMPLETION_TIMER)
				.tag("event_type", event.type().name())
				.register(meterRegistry)
				.record(Duration.between(event.payload().occurredAt(), IngestionClock.now()));
	}

	/** 상한 소진 격리. 시도 횟수는 상한값 그 자체다 - 소진했다는 사실이 곧 그만큼 시도했다는 뜻. */
	private void isolate(EventRecord event, MapRecord<String, String, String> message, DeadLetter.Failure failure) {
		deadLetterService.isolate(event.payload(), message.getStream(), message.getId().getValue(), failure,
				properties.maxAttempts());
		acknowledge(message);
		log.error("재시도 상한을 넘겨 격리했습니다. type={} aggregateId={} step={}", event.type(), event.aggregateId(),
				failure.step());
	}

	private void acknowledge(MapRecord<String, String, String> message) {
		streamOperations().acknowledge(properties.consumerGroup(), message);
	}

	private StreamOperations<String, String, String> streamOperations() {
		return redisTemplate.opsForStream();
	}
}
