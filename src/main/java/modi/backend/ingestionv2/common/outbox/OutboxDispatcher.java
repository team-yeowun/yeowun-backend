package modi.backend.ingestionv2.common.outbox;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.lock.RedisMarkerLock;
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
 *   <li>중복 판정은 선점 전략이 소유 - 운영 기본은 행마다 Redis 마커를 잡고, 못 잡은 행은 발행도 표시도 하지 않고 건너뛴다.
 *       건너뛴 행은 마커를 잡은 인스턴스가 같은 틱에 처리한다</li>
 *   <li>발행에 성공한 행의 마커는 해제하지 않는다 - 그 행은 곧 SENT 가 되어 다시 조회되지 않고, TTL 이 지나면
 *       알아서 사라진다. 발행 직후 해제하면 아직 커밋되지 않은 행을 다른 인스턴스가 다시 집는다</li>
 *   <li>발행에 실패한 행의 마커는 그 자리에서 해제한다 - 그 행은 미발행으로 남아 다음 틱이 다시 집어야 하는데,
 *       마커를 그대로 두면 TTL 이 지날 때까지 아무 인스턴스도 손대지 못해 재시도가 통째로 멈춘다.
 *       대가는 발행 도중 응답만 유실된 경우의 재발행 가능성이고, 이 계층은 원래 at-least-once 다</li>
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

	/** 선점 조회 호출 횟수 - 태그 rows 로 빈 조회(empty)와 집은 조회(nonempty)를 가른다. 빈 조회의 비중이 곧 유휴 폴링 비용이다. */
	public static final String CLAIM_COUNTER = "ingestion.outbox.claim";

	/** 적재(created_at)부터 발송(sent_at)까지 - 발송 단계의 지연. 폴백 틱 간격을 늘려도 이 값이 늘지 않아야 깨우기가 일한 것이다. */
	public static final String LATENCY_TIMER = "ingestion.outbox.dispatch.latency";

	/** 선점 조회가 집어 온 행 수 합계 - 호출당 평균이 배치 상한에 얼마나 닿는지. */
	public static final String CLAIM_ROWS_COUNTER = "ingestion.outbox.claim.rows";

	/** 행 마커 판정 결과. acquired = 이 인스턴스가 맡은 행, skipped = 다른 인스턴스에 내준 행(읽기 낭비). */
	public static final String MARKER_COUNTER = "ingestion.outbox.marker";

	/** 행 마커 키 접두 - 잡 단위 락(lock:)과 갈라 두어 관측 스크립트가 둘을 따로 셀 수 있게 한다. */
	private static final String MARKER_KEY_PREFIX = "outbox:";

	private final OutboxService outboxService;
	private final EventDispatcher eventDispatcher;
	private final RedisMarkerLock markerLock;
	private final IngestionProperties properties;
	private final MeterRegistry meterRegistry;

	/** 발송 한 배치 - 미발행 행을 배치 크기만큼 선점해 내보낸다. 소진 루프가 읽는 것은 이 두 수다. */
	@Transactional
	public OutboxDispatchOutcome dispatchBatch() {
		return dispatchOnce();
	}

	/** 발송 한 배치 - 돌려주는 값은 이 인스턴스가 맡아 처리한 행 수. */
	@Transactional
	public int dispatchPending() {
		return dispatchOnce().published();
	}

	private OutboxDispatchOutcome dispatchOnce() {
		OutboxClaimStrategy strategy = properties.claimStrategy();
		List<Outbox> claimed = outboxService.claimPending(properties.dispatchBatchSize(), strategy,
				properties.outboxRead());
		claimCounter(strategy, claimed.isEmpty() ? "empty" : "nonempty").increment();
		claimRowsCounter(strategy).increment(claimed.size());

		boolean marking = strategy.usesMarker();
		List<Outbox> mine = marking ? acquireMarkers(claimed) : claimed;
		mine.forEach(outbox -> send(outbox, marking));
		return OutboxDispatchOutcome.of(claimed.size(), mine.size());
	}

	/** 마커를 잡은 행만 남긴다. 못 잡은 행은 이 틱에서 다시 시도하지 않는다 - 같은 행을 붙잡고 도는 것이 곧 읽기 낭비다. */
	private List<Outbox> acquireMarkers(List<Outbox> claimed) {
		Duration ttl = Duration.ofMillis(properties.markerTtlMs());
		String owner = properties.consumerName();
		List<Outbox> mine = new ArrayList<>(claimed.size());
		int skipped = 0;
		for (Outbox outbox : claimed) {
			if (markerLock.tryAcquire(MARKER_KEY_PREFIX + outbox.getId(), owner, ttl)) {
				mine.add(outbox);
			} else {
				skipped++;
			}
		}
		markerCounter("acquired").increment(mine.size());
		markerCounter("skipped").increment(skipped);
		return mine;
	}

	private void send(Outbox outbox, boolean marking) {
		if (IngestionStream.of(outbox.getEventType()).isEmpty()) {
			// 소비자가 없는 종결 사실은 스트림을 거치지 않는다. 여기서 닫지 않으면 PENDING인 채로 매 틱 다시 선점된다.
			outboxService.markSentWithoutStream(outbox, IngestionClock.now());
			return;
		}
		try {
			LocalDateTime now = IngestionClock.now();
			eventDispatcher.dispatch(outbox.toPayload());
			outboxService.markSent(outbox, now);
			publishCounter("success").increment();
			Timer.builder(LATENCY_TIMER).tag("event_type", outbox.getEventType().name())
					.publishPercentileHistogram().register(meterRegistry)
					.record(Duration.between(outbox.getCreatedAt(), now));
		} catch (RuntimeException failure) {
			outboxService.markPublishFailed(outbox, properties.maxAttempts());
			publishCounter("failure").increment();
			if (marking) {
				markerLock.release(MARKER_KEY_PREFIX + outbox.getId(), properties.consumerName());
			}
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

	private Counter claimCounter(OutboxClaimStrategy strategy, String rows) {
		return Counter.builder(CLAIM_COUNTER).tag("strategy", strategy.name()).tag("rows", rows).register(meterRegistry);
	}

	private Counter claimRowsCounter(OutboxClaimStrategy strategy) {
		return Counter.builder(CLAIM_ROWS_COUNTER).tag("strategy", strategy.name()).register(meterRegistry);
	}

	private Counter markerCounter(String result) {
		return Counter.builder(MARKER_COUNTER).tag("result", result).register(meterRegistry);
	}
}
