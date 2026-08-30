package modi.backend.ingestionv2.lab.retry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.test.context.TestPropertySource;

import io.micrometer.core.instrument.MeterRegistry;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.StreamGroupInitializer;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.common.queue.PendingReclaimer;
import modi.backend.ingestionv2.common.queue.ReclaimBackoff;
import modi.backend.ingestionv2.common.queue.ReclaimJitter;
import modi.backend.ingestionv2.common.queue.StreamConsumer;

/**
 * step-04 소비 재전달 백오프·지터 - 주 실험.
 *
 * <ul>
 *   <li>변형 셋은 <b>회수 자격 규칙</b>만 다르다 - base·배치·실패 주입·시드가 전부 같다</li>
 *   <li>B0 은 lab 고정 간격 회수기(after 정책 미참조), B1·B2 는 프로덕션 회수기에 설정만 갈아끼운 것이다.
 *       설정을 갈아끼우려고 컨텍스트를 셋 띄우지 않고 <b>설정 레코드를 복사해</b> 회수기를 새로 만든다 -
 *       기동 시간이 측정 시간을 넘지 않게</li>
 *   <li>1차 증거는 <b>핸들러 진입 시각</b>이다 - 메트릭 카운터는 대조식의 한쪽일 뿐 시계열의 원본이 아니다</li>
 *   <li>시간축이 프로덕션의 1/60(idle 60s → 1s)이라 주장은 <b>절대 rate 가 아니라 곡선의 모양과
 *       변형 간 상대 peak 비</b>까지만 한다. 이 문장은 원시 파일 조건에도 실린다</li>
 *   <li>회수 틱은 실제로 1초씩 흐른다 - 자격 판정의 입력이 Redis 가 재는 경과 시간이라 시간을 앞당길 수 없다</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.retry.ReclaimBackoffLab"}
 */
@Import(RetryLabHandlers.class)
@TestPropertySource(properties = {
		"app.ingestion.v2.reclaim-idle-seconds=1",
		"app.ingestion.v2.reclaim-max-idle-seconds=30",
		"app.ingestion.v2.reclaim-batch-size=10000",
		"app.ingestion.v2.dispatch-batch-size=1000"
})
@DisplayName("[lab] step-04 소비 재전달 백오프·지터")
class ReclaimBackoffLab extends RetryLabSupport {

	private static final String RECLAIM_COUNTER = PendingReclaimer.RECLAIM_COUNTER;
	private static final int READ_BATCH = 500;
	private static final long BUCKET_MS = 100L;

	@Autowired private FaultInjectingEventHandler labHandler;
	@Autowired private StreamConsumer streamConsumer;
	@Autowired private StreamGroupInitializer streamGroupInitializer;
	@Autowired private MeterRegistry meterRegistry;

	@BeforeEach
	void resetPipeline() {
		clearSliceTables();
		redisTemplate.delete(List.of(IngestionStream.values()).stream().map(IngestionStream::key).toList());
		streamGroupInitializer.afterPropertiesSet();
		labHandler.reset();
	}

	@Test
	@DisplayName("조건 A(실패율 30%) - B0/B1/B2 의 초당 재전달 시계열을 잰다")
	void 조건A_실패율30() {
		for (Variant variant : Variant.values()) {
			measure("step-04/condition-A", variant, FaultInjectingEventHandler.Mode.RATE);
		}
	}

	@Test
	@DisplayName("조건 B(장애창 100% 실패) - B0/B1/B2 의 재전달 집중도를 잰다")
	void 조건B_장애창() {
		for (Variant variant : Variant.values()) {
			measure("step-04/condition-B", variant, FaultInjectingEventHandler.Mode.OUTAGE);
		}
	}

	private void measure(String step, Variant variant, FaultInjectingEventHandler.Mode mode) {
		RetryLabRaw raw = new RetryLabRaw(step, variant.name());
		raw.baseCondition(conditionOf(variant, mode));
		raw.note("B0 은 lab 고정 간격 회수기다. after 의 정책 객체를 참조하지 않는다 - 참조하면 before 가 "
				+ "after 의 규칙을 물려받아 '약화된 after' 가 된다.");
		raw.note("시간축 축소: 프로덕션 idle 60s / 회수 폴링 30s 를 여기서는 1s / 1s 로 줄였다(idle 1/60, 폴링 1/30). "
				+ "따라서 절대 rate 가 아니라 곡선의 모양과 변형 간 상대 peak 비까지만 주장한다.");

		List<Double> peakPerSecond = new ArrayList<>();
		List<Double> windowTotal = new ArrayList<>();
		List<Double> topBucketShare = new ArrayList<>();
		List<Double> bucketStdev = new ArrayList<>();
		List<Double> idlePolls = new ArrayList<>();
		List<Double> pollMillis = new ArrayList<>();
		List<Double> completed = new ArrayList<>();
		List<Double> requestedMinusClaimed = new ArrayList<>();
		Map<Long, Integer> lastBuckets = Map.of();

		for (int index = 0; index < RetryLabConfig.warmup() + RetryLabConfig.runs(); index++) {
			RunResult result = runOnce(variant, mode);
			if (index < RetryLabConfig.warmup()) {
				continue;
			}
			peakPerSecond.add(result.peakPerSecond());
			windowTotal.add((double) result.redeliveries());
			topBucketShare.add(result.topSecondShare());
			bucketStdev.add(RetryLabStats.of(toArray(result.bucketCounts())).stdev());
			idlePolls.add((double) result.idlePolls());
			pollMillis.add(result.pollMillisP95());
			completed.add((double) result.completed());
			requestedMinusClaimed.add((double) result.requestedMinusClaimed());
			lastBuckets = result.buckets();
		}

		Map<String, Object> empty = Map.of();
		raw.series("redelivery_peak_per_second", "초당 재전달 요청 수 peak", "req/s", toArray(peakPerSecond), empty);
		raw.series("redelivery_window_total", "관측창 재전달 합계", "건", toArray(windowTotal), empty);
		raw.series("top_second_share", "최다 1초 점유율", "%", toArray(topBucketShare), empty);
		raw.series("bucket_stdev", "100ms 버킷 표준편차", "건", toArray(bucketStdev), empty);
		raw.series("reclaim_idle_polls", "회수 공회전 횟수(claim 0건 폴링)", "회", toArray(idlePolls), empty);
		raw.series("reclaim_poll_p95", "회수 폴링 1회 소요 p95", "ms", toArray(pollMillis), empty);
		raw.series("completed", "처리 완료 건수", "건", toArray(completed), empty);
		raw.series("requested_minus_claimed", "회수 요청 수 - 실제 claim 수", "건",
				toArray(requestedMinusClaimed), empty);

		raw.observation("bucket_histogram_last_run", Map.of(
				"description", "마지막 본측정 run 의 100ms 버킷 히스토그램. key=버킷 시작(ms), value=핸들러 진입 수.",
				"bucket_ms", BUCKET_MS,
				"buckets", new TreeMap<>(lastBuckets)));
		raw.attach("before-check.txt", beforeCheck(variant));
		raw.finish();
	}

	private RunResult runOnce(Variant variant, FaultInjectingEventHandler.Mode mode) {
		resetPipeline();
		labHandler.configure(mode, RetryLabConfig.reclaimFailurePercent(),
				RetryLabConfig.reclaimOutageSeconds() * 1_000_000_000L, RetryLabConfig.reclaimFaultSeed());

		int rows = RetryLabConfig.reclaimRows();
		seedOutbox(rows);
		while (outboxDispatcher.dispatchPending() > 0) {
			// 스트림 적재. 적재 시간은 관측창 밖이다.
		}

		double claimedBefore = counterValue("claimed");
		double skippedBefore = counterValue("skipped");
		labHandler.startWindow();

		// 최초 전달 - 리스너 컨테이너의 읽기 루프를 손으로 대신한다.
		for (List<MapRecord<String, String, String>> batch = read(); !batch.isEmpty(); batch = read()) {
			batch.forEach(streamConsumer::onMessage);
		}

		FixedIntervalReclaimer fixed = variant == Variant.B0
				? new FixedIntervalReclaimer(redisTemplate, properties, meterRegistry, streamConsumer::onMessage)
				: null;
		PendingReclaimer exponential = variant == Variant.B0 ? null : new PendingReclaimer(
				redisTemplate, streamConsumer, propertiesWith(variant), meterRegistry);

		long windowNanos = RetryLabConfig.reclaimWindowSeconds() * 1_000_000_000L;
		long tickMs = RetryLabConfig.reclaimTickMs();
		long started = System.nanoTime();
		int idlePolls = 0;
		List<Double> pollSamples = new ArrayList<>();
		int handledBeforeTick = labHandler.attempts().size();
		while (System.nanoTime() - started < windowNanos) {
			long tickStarted = System.nanoTime();
			if (fixed != null) {
				FixedIntervalReclaimer.Tick tick = fixed.reclaimAll();
				pollSamples.add(tick.pollMillis());
			} else {
				exponential.reclaimAll();
				pollSamples.add((System.nanoTime() - tickStarted) / 1_000_000d);
			}
			int handledAfterTick = labHandler.attempts().size();
			if (handledAfterTick == handledBeforeTick) {
				idlePolls++;
			}
			handledBeforeTick = handledAfterTick;
			sleepUntil(tickStarted + tickMs * 1_000_000L);
		}

		double claimed = counterValue("claimed") - claimedBefore;
		double skipped = counterValue("skipped") - skippedBefore;
		return RunResult.of(labHandler.attempts(), rows, idlePolls, pollSamples, labHandler.successCount(),
				(int) Math.round(skipped), (int) Math.round(claimed));
	}

	/** 프로덕션 회수기에 넘길 설정 - 회수 정책 두 칸만 바꾸고 나머지는 그대로 복사한다. */
	private IngestionProperties propertiesWith(Variant variant) {
		return new IngestionProperties(
				properties.enabled(), properties.autoDelivery(), properties.consumerGroup(),
				properties.consumerName(), properties.dispatchBatchSize(), properties.externalStreamConsumers(),
				properties.dbStreamConsumers(), properties.pollTimeoutMs(), properties.readBatchSize(),
				properties.reclaimIdleSeconds(), properties.reclaimMaxIdleSeconds(),
				variant.backoff(), variant.jitter(), properties.reclaimBatchSize(),
				properties.streamMaxLength(), properties.maxAttempts(), properties.retentionDays(),
				properties.cleanupBatchSize());
	}

	private Map<String, Object> conditionOf(Variant variant, FaultInjectingEventHandler.Mode mode) {
		Map<String, Object> condition = new LinkedHashMap<>(commonCondition());
		condition.put("variant", variant.name());
		condition.put("eligibility_rule", variant.rule());
		condition.put("reclaimer", variant == Variant.B0
				? "lab FixedIntervalReclaimer" : "프로덕션 PendingReclaimer");
		condition.put("rows", RetryLabConfig.reclaimRows());
		condition.put("event_type", IngestionEventType.DETAIL_READY.name());
		condition.put("failure_mode", mode.name());
		condition.put("failure_rate", mode == FaultInjectingEventHandler.Mode.RATE
				? RetryLabConfig.reclaimFailurePercent() + "%" : "장애창 100%");
		condition.put("outage_seconds", mode == FaultInjectingEventHandler.Mode.OUTAGE
				? RetryLabConfig.reclaimOutageSeconds() : 0);
		condition.put("reclaim_base_seconds", properties.reclaimIdleSeconds());
		condition.put("reclaim_max_idle_seconds", properties.reclaimMaxIdleSeconds());
		condition.put("reclaim_batch_size", properties.reclaimBatchSize());
		condition.put("window_s", RetryLabConfig.reclaimWindowSeconds());
		condition.put("tick_ms", RetryLabConfig.reclaimTickMs());
		condition.put("fault_seed", RetryLabConfig.reclaimFaultSeed());
		condition.put("threads", 1);
		condition.put("lock", "-");
		condition.put("before_note", "B0 은 과거의 코드가 아니라 비교 대상 정책(고정 간격)을 재구성한 기준선이다. "
				+ "이 실험은 사건 재현이 아니라 재시도 정책 두 갈래의 설계 비교다.");
		condition.put("time_scale_note", "idle 1/60·폴링 1/30 축소. 절대 rate 가 아니라 곡선의 모양과 상대 peak 비까지만 주장한다.");
		return condition;
	}

	private String beforeCheck(Variant variant) {
		return "git rev-parse --short HEAD -> " + gitCommit() + "\n"
				+ "variant=" + variant.name() + " rule=" + variant.rule() + "\n"
				+ "reclaimer=" + (variant == Variant.B0 ? "lab FixedIntervalReclaimer" : "프로덕션 PendingReclaimer")
				+ "\n"
				+ "app.ingestion.v2.reclaim-backoff(적용값)=" + variant.backoff() + "\n"
				+ "app.ingestion.v2.reclaim-jitter(적용값)=" + variant.jitter() + "\n"
				+ "app.ingestion.v2.reclaim-idle-seconds=" + properties.reclaimIdleSeconds() + "\n"
				+ "app.ingestion.v2.reclaim-batch-size=" + properties.reclaimBatchSize() + "\n";
	}

	private double counterValue(String result) {
		return meterRegistry.find(RECLAIM_COUNTER).tag("result", result).counters().stream()
				.mapToDouble(io.micrometer.core.instrument.Counter::count)
				.sum();
	}

	private List<MapRecord<String, String, String>> read() {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		List<MapRecord<String, String, String>> records = streamOperations.read(
				Consumer.from(properties.consumerGroup(), properties.consumerName()),
				StreamReadOptions.empty().count(READ_BATCH),
				StreamOffset.create(IngestionStream.CULTURE.key(), ReadOffset.lastConsumed()));
		return records == null ? List.of() : records;
	}

	private void seedOutbox(int rows) {
		List<Object[]> batch = new ArrayList<>();
		for (int index = 0; index < rows; index++) {
			String aggregateId = "LAB04-" + index;
			OutboxPayload payload = OutboxPayload.of(IngestionEventType.DETAIL_READY, aggregateId,
					IngestionClock.now());
			batch.add(new Object[] {
					IngestionEventType.DETAIL_READY.aggregateType().name(), aggregateId,
					IngestionEventType.DETAIL_READY.name(), payload.toJson(), "PENDING", 0,
					IngestionClock.now()});
		}
		jdbcTemplate.batchUpdate("""
				insert into ingestion_outbox
				  (aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at)
				values (?, ?, ?, ?, ?, ?, ?)
				""", batch);
	}

	private static void sleepUntil(long deadlineNanos) {
		long remaining = deadlineNanos - System.nanoTime();
		if (remaining <= 0) {
			return;
		}
		try {
			Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static double[] toArray(List<Double> values) {
		double[] array = new double[values.size()];
		for (int index = 0; index < values.size(); index++) {
			array[index] = values.get(index);
		}
		return array;
	}

	/** 계획서 변형 셋. 자격 규칙 외에는 아무것도 다르지 않다. */
	private enum Variant {

		B0(null, null, "idle >= base (고정)"),
		B1(ReclaimBackoff.EXPONENTIAL, ReclaimJitter.NONE, "idle >= base x 2^(전달횟수-1), 상한까지"),
		B2(ReclaimBackoff.EXPONENTIAL, ReclaimJitter.FULL, "B1 의 지연에 FULL 지터(바닥은 base)");

		private final ReclaimBackoff backoff;
		private final ReclaimJitter jitter;
		private final String rule;

		Variant(ReclaimBackoff backoff, ReclaimJitter jitter, String rule) {
			this.backoff = backoff;
			this.jitter = jitter;
			this.rule = rule;
		}

		ReclaimBackoff backoff() {
			return backoff;
		}

		ReclaimJitter jitter() {
			return jitter;
		}

		String rule() {
			return rule;
		}
	}

	/** run 한 번의 산출물. 시계열은 핸들러 진입 시각에서 만든다. */
	private record RunResult(int redeliveries, double peakPerSecond, double topSecondShare,
			Map<Long, Integer> buckets, List<Double> bucketCounts, int idlePolls, double pollMillisP95,
			int completed, int requestedMinusClaimed) {

		static RunResult of(List<FaultInjectingEventHandler.Attempt> attempts, int rows, int idlePolls,
				List<Double> pollSamples, int completed, int skipped, int claimed) {
			// claimed 는 조건 기록용으로만 쓴다 - 시계열의 원본은 핸들러 진입 시각이다.
			Map<Long, Integer> seconds = new TreeMap<>();
			Map<Long, Integer> buckets = new TreeMap<>();
			int redeliveries = 0;
			for (int index = rows; index < attempts.size(); index++) {
				FaultInjectingEventHandler.Attempt attempt = attempts.get(index);
				redeliveries++;
				seconds.merge((long) (attempt.elapsedMillis() / 1_000L), 1, Integer::sum);
				buckets.merge((long) (attempt.elapsedMillis() / BUCKET_MS) * BUCKET_MS, 1, Integer::sum);
			}
			int peak = seconds.values().stream().mapToInt(Integer::intValue).max().orElse(0);
			double share = redeliveries == 0 ? 0 : peak * 100d / redeliveries;
			List<Double> bucketCounts = buckets.values().stream().map(Integer::doubleValue).toList();
			return new RunResult(redeliveries, peak, share, buckets, bucketCounts, idlePolls,
					RetryLabStats.of(toArray(pollSamples)).p95(), completed, skipped);
		}
	}
}
