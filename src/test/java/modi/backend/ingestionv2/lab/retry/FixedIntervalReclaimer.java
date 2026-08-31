package modi.backend.ingestionv2.lab.retry;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.common.queue.PendingReclaimer;

/**
 * step-04 기준선(B0) - <b>고정 간격</b> 회수기. 지연이 늘지 않는다.
 *
 * <ul>
 *   <li>자격 규칙은 {@code idle >= base} 하나 - 전달 횟수를 보지 않으므로 재전달 간격이 영원히 같다</li>
 *   <li>after 의 백오프 정책 객체를 <b>참조하지 않는다</b> - 참조하면 before 가 after 의 규칙을 물려받아
 *       "약화된 after" 가 된다(프로토콜 §2 금지 항목)</li>
 *   <li>{@code ingestion.stream.reclaim} 카운터를 프로덕션과 <b>같은 이름·같은 태그</b>로 올린다 -
 *       세 변형의 회수 요청 수를 한 자로 재기 위함(계획서 NEW-01)</li>
 *   <li>배치 크기·base 는 프로덕션 프로퍼티를 그대로 읽는다 - 세 변형이 같은 값을 쓴다는 조건을 구조로 강제</li>
 *   <li>XPENDING 한 번의 소요 시간을 따로 돌려준다 - 배치 10,000 에서 응답이 커지는 만큼 이 값이 지표가 된다</li>
 * </ul>
 */
final class FixedIntervalReclaimer {

	static final String RECLAIM_COUNTER = PendingReclaimer.RECLAIM_COUNTER;

	private final StringRedisTemplate redisTemplate;
	private final IngestionProperties properties;
	private final MeterRegistry meterRegistry;
	private final Consumer<MapRecord<String, String, String>> consumer;

	FixedIntervalReclaimer(StringRedisTemplate redisTemplate, IngestionProperties properties,
			MeterRegistry meterRegistry, Consumer<MapRecord<String, String, String>> consumer) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		this.consumer = consumer;
	}

	/** 회수 한 틱 - 네 스트림 전부. */
	Tick reclaimAll() {
		long started = System.nanoTime();
		int requested = 0;
		int claimedCount = 0;
		long pollNanos = 0;
		for (IngestionStream stream : IngestionStream.values()) {
			Tick tick = reclaim(stream);
			requested += tick.requested();
			claimedCount += tick.claimed();
			pollNanos += tick.pollNanos();
		}
		return new Tick(requested, claimedCount, pollNanos, System.nanoTime() - started);
	}

	private Tick reclaim(IngestionStream stream) {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		long pollStarted = System.nanoTime();
		PendingMessages pending = streamOperations.pending(stream.key(), properties.consumerGroup(),
				Range.unbounded(), properties.reclaimBatchSize());
		long pollNanos = System.nanoTime() - pollStarted;
		if (pending == null || pending.isEmpty()) {
			return new Tick(0, 0, pollNanos, pollNanos);
		}

		Duration minIdle = Duration.ofSeconds(properties.reclaimIdleSeconds());
		List<RecordId> targets = pending.stream()
				.filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
				.map(PendingMessage::getId)
				.toList();
		if (targets.isEmpty()) {
			return new Tick(0, 0, pollNanos, pollNanos);
		}

		List<MapRecord<String, String, String>> claimed = streamOperations.claim(stream.key(),
				properties.consumerGroup(), properties.consumerName() + "-reclaim", minIdle,
				targets.toArray(new RecordId[0]));
		int claimedCount = claimed == null ? 0 : claimed.size();
		count(stream, "claimed", claimedCount);
		count(stream, "skipped", targets.size() - claimedCount);
		if (claimed != null) {
			claimed.forEach(consumer);
		}
		return new Tick(targets.size(), claimedCount, pollNanos, pollNanos);
	}

	private void count(IngestionStream stream, String result, int amount) {
		if (amount <= 0) {
			return;
		}
		Counter.builder(RECLAIM_COUNTER)
				.tag("stream", stream.key())
				.tag("result", result)
				.register(meterRegistry)
				.increment(amount);
	}

	/** 회수 한 틱의 산출물 - 요청 수·성공 수·XPENDING 소요·틱 전체 소요. */
	record Tick(int requested, int claimed, long pollNanos, long totalNanos) {

		double pollMillis() {
			return pollNanos / 1_000_000d;
		}

		double totalMillis() {
			return totalNanos / 1_000_000d;
		}
	}
}
