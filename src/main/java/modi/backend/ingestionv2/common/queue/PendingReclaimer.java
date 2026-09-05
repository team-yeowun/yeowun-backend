package modi.backend.ingestionv2.common.queue;

import java.time.Duration;
import java.util.List;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionProperties;

/**
 * 미처리 항목 회수.
 *
 * <ul>
 *   <li>선정 기준은 마지막 전달 이후 경과 시간 - 시계 스큐와 무관</li>
 *   <li>전달 횟수는 얼마나 기다릴지를 정하는 데만 씀 - 격리 판정에는 여전히 쓰지 않음(도메인 몫)</li>
 *   <li>기다리는 규칙은 정책 객체가 소유 - 회수기는 대기열을 다루는 일만</li>
 *   <li>XCLAIM 가드에는 base(정책의 최솟값)만 넘김 - 지수 임계치를 가드로 넘기면 요청한 항목의 일부를
 *       Redis 가 조용히 돌려주지 않아 회수 요청 수와 실제 회수 수가 어긋남</li>
 *   <li>회수한 항목은 곧바로 소비 경로로 - 경로가 갈라지지 않게</li>
 *   <li>요청 수(claimed + skipped)와 성공 수(claimed)를 함께 셈 - 둘이 어긋나면 자격 판정이 가드를 넘어섰다는 뜻이라
 *       그 차이 자체가 정책 구현의 회귀 감시</li>
 * </ul>
 */
@Slf4j
@Component
public class PendingReclaimer {

	/** 회수·재전달 요청 수. 태그 result 로 실제 회수(claimed)와 못 받은 것(skipped)을 가른다. */
	public static final String RECLAIM_COUNTER = "ingestion.stream.reclaim";

	private final StringRedisTemplate redisTemplate;
	private final StreamConsumer streamConsumer;
	private final IngestionProperties properties;
	private final MeterRegistry meterRegistry;
	private final ReclaimBackoffPolicy policy;

	public PendingReclaimer(StringRedisTemplate redisTemplate, StreamConsumer streamConsumer,
			IngestionProperties properties, MeterRegistry meterRegistry) {
		this.redisTemplate = redisTemplate;
		this.streamConsumer = streamConsumer;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		this.policy = new ReclaimBackoffPolicy(properties.reclaimBackoff(), properties.reclaimJitter(),
				Duration.ofSeconds(properties.reclaimIdleSeconds()),
				Duration.ofSeconds(properties.reclaimMaxIdleSeconds()));
	}

	public void reclaimAll() {
		for (IngestionStream stream : IngestionStream.values()) {
			reclaim(stream);
		}
	}

	private void reclaim(IngestionStream stream) {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		PendingMessages pending = streamOperations.pending(stream.key(), properties.consumerGroup(),
				Range.unbounded(), properties.reclaimBatchSize());
		if (pending == null || pending.isEmpty()) {
			return;
		}

		Duration guard = policy.base();
		List<RecordId> targets = pending.stream()
				.filter(message -> policy.eligible(message.getElapsedTimeSinceLastDelivery(),
						message.getTotalDeliveryCount(), jitterKeyOf(message)))
				.map(PendingMessage::getId)
				.toList();
		if (targets.isEmpty()) {
			return;
		}

		List<MapRecord<String, String, String>> claimed = streamOperations.claim(stream.key(),
				properties.consumerGroup(), properties.consumerName() + "-reclaim", guard,
				targets.toArray(new RecordId[0]));
		int claimedCount = claimed == null ? 0 : claimed.size();
		count(stream, "claimed", claimedCount);
		count(stream, "skipped", targets.size() - claimedCount);
		if (claimed == null || claimed.isEmpty()) {
			return;
		}
		log.warn("방치된 항목을 회수했습니다. stream={} count={}", stream.key(), claimed.size());
		claimed.forEach(streamConsumer::onMessage);
	}

	/** 지터를 항목마다 고정하는 열쇠 - 레코드 id 는 스트림 안에서 유일하고 재전달에도 바뀌지 않는다. */
	private static long jitterKeyOf(PendingMessage message) {
		RecordId id = message.getId();
		return id == null ? 0L : id.getValue().hashCode();
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
}
