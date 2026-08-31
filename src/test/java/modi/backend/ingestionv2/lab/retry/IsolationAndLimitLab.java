package modi.backend.ingestionv2.lab.retry;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.test.context.TestPropertySource;

import io.micrometer.core.instrument.MeterRegistry;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionDeliveryCriteria;
import modi.backend.ingestionv2.common.StreamGroupInitializer;
import modi.backend.ingestionv2.common.deadletter.DeadLetterService;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.queue.EventDispatcher;
import modi.backend.ingestionv2.common.queue.IngestionEventRouter;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.common.queue.StreamConsumer;

/**
 * step-05 상한·유실·격리·재처리 정합.
 *
 * <ul>
 *   <li>시나리오 A(O-01) - 발행 장애를 켠 채 틱을 밀어 PENDING 이 FAILED 로 쓸려 나가는 속도를 관측한다.
 *       <b>합격 기준이 아니라 기록</b>이고, 처방은 쓰지 않는다(관측만 기록)</li>
 *   <li>시나리오 B - 소진 신호를 받은 뒤 격리하는 현행(after)과 격리 없이 확인만 하는 before 를 나란히 잰다.
 *       before 의 유실이 0 이 아니어야 "유실 경로 제거" 주장이 성립한다(게이트 F-04)</li>
 *   <li>시나리오 C - 격리 행을 되돌려 보내 배달 계층 회생(L1)을 잰다. 도메인 회생(L2)은 이 하네스의
 *       관측 범위 밖이다(핸들러가 도메인이 아니다) - 사실만 기록하고 판단은 남기지 않는다</li>
 *   <li>발행 배치 500·틱 1s 는 계획서 값. 틱은 손으로 민다 - 스케줄러가 끼면 시계열이 흐트러진다</li>
 *   <li><b>step-08 채취</b>가 시나리오 B·C 에 함께 붙는다 - 계획서 실행순서 7번("step-05 B·C 만 재실행해
 *       격리·재처리 메트릭을 채운다")이 이 두 시나리오를 메트릭 run 으로 지정했다. 같은 run 에서 카운터 델타와
 *       DB 실측을 함께 떠야 대조가 성립한다(따로 돌리면 두 run 의 차이가 대조 오차로 둔갑한다)</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.retry.IsolationAndLimitLab"}
 */
@Import(RetryLabHandlers.class)
@TestPropertySource(properties = {
		"app.ingestion.v2.dispatch-batch-size=500"
})
@DisplayName("[lab] step-05 상한·유실·격리·재처리")
class IsolationAndLimitLab extends RetryLabSupport {

	private static final String STEP = "step-05";
	private static final int OUTBOX_ROWS = 10_000;
	private static final int CONSUME_ROWS = 1_000;
	private static final int TICK_GUARD = 200;
	private static final long TICK_INTERVAL_MS = 1_000L;
	private static final int READ_BATCH = 200;

	/** 계기 표본 주기. 계획서 8-3 이 정한 값이라 조건에도 이 상수를 적는다. */
	private static final long GAUGE_SAMPLE_MS = 1_000L;

	@Autowired private FaultInjectingEventHandler labHandler;
	@Autowired private EventDispatcher eventDispatcher;
	@Autowired private StreamConsumer streamConsumer;
	@Autowired private IngestionEventRouter eventRouter;
	@Autowired private StreamGroupInitializer streamGroupInitializer;
	@Autowired private MeterRegistry meterRegistry;

	@BeforeEach
	void resetPipeline() {
		clearSliceTables();
		redisTemplate.delete(List.of(IngestionStream.values()).stream().map(IngestionStream::key).toList());
		streamGroupInitializer.afterPropertiesSet();
		labHandler.reset();
		toggle().reset();
	}

	@Test
	@DisplayName("A. 발행 장애 구간에서 PENDING 이 FAILED 로 쓸려 나가는 속도를 관측한다")
	void 시나리오A_발행_상한_관측() {
		RetryLabRaw raw = new RetryLabRaw(STEP, "scenario-A-outbox-limit");
		Map<String, Object> condition = new LinkedHashMap<>(commonCondition());
		condition.put("rows", OUTBOX_ROWS);
		condition.put("dispatch_batch_size", properties.dispatchBatchSize());
		condition.put("max_attempts", properties.maxAttempts());
		condition.put("tick_interval_ms", TICK_INTERVAL_MS);
		condition.put("failure_injection", "발행 어댑터 100% 실패");
		condition.put("threads", 1);
		condition.put("lock", "-");
		condition.put("before_note", "관측 기록이며 합격 기준이 아니다. 처방(일괄 재시도 API·상한 조정·발행 백오프)은 쓰지 않는다.");
		raw.baseCondition(condition);
		raw.note("틱은 실시간 대기 없이 연속으로 민다. 경과 시간은 '틱 수 x 틱 주기'로 환산한 값이며 벽시계 시간과 다르다.");

		seedOutbox(OUTBOX_ROWS);
		toggle().failing(true);

		List<Double> failedPerTick = new ArrayList<>();
		List<Integer> failedCumulative = new ArrayList<>();
		int ticksToAllFailed = -1;
		long previousFailed = 0;
		for (int tick = 1; tick <= TICK_GUARD; tick++) {
			outboxDispatcher.dispatchPending();
			long failed = countOutbox("FAILED");
			failedPerTick.add((double) (failed - previousFailed));
			failedCumulative.add((int) failed);
			previousFailed = failed;
			if (countOutbox("PENDING") == 0) {
				ticksToAllFailed = tick;
				break;
			}
		}

		toggle().failing(false);
		long sentAfterRecovery = 0;
		for (int tick = 0; tick < 3; tick++) {
			outboxDispatcher.dispatchPending();
			sentAfterRecovery = countOutbox("SENT");
		}

		raw.series("failed_per_tick", "틱별 FAILED 전환 건수", "건", toArray(failedPerTick), Map.of());
		Map<String, Object> sweep = new LinkedHashMap<>();
		sweep.put("failed_cumulative_by_tick", failedCumulative);
		sweep.put("failed_at_tick3", failedCumulative.size() >= 3 ? failedCumulative.get(2) : null);
		sweep.put("failed_at_tick10", failedCumulative.size() >= 10 ? failedCumulative.get(9) : null);
		sweep.put("ticks_to_all_failed", ticksToAllFailed);
		sweep.put("elapsed_ms_by_formula", ticksToAllFailed < 0 ? null : ticksToAllFailed * TICK_INTERVAL_MS);
		sweep.put("formula_rows_per_tick", properties.dispatchBatchSize() / (double) properties.maxAttempts());
		sweep.put("measured_rows_per_tick",
				ticksToAllFailed <= 0 ? null : OUTBOX_ROWS / (double) ticksToAllFailed);
		sweep.put("sent_after_recovery", sentAfterRecovery);
		sweep.put("admin_single_retry_calls_needed", countOutbox("FAILED"));
		raw.observation("outage_sweep", sweep);
		raw.finish();
	}

	@Test
	@DisplayName("B. 격리하는 현행과 격리 없이 확인만 하는 before 의 유실을 나란히 잰다")
	void 시나리오B_격리와_유실() {
		measureIsolation(true);
		resetPipeline();
		measureIsolation(false);
	}

	private void measureIsolation(boolean isolating) {
		RetryLabRaw raw = new RetryLabRaw(STEP, isolating ? "scenario-B-after" : "scenario-B-before");
		Map<String, Object> condition = new LinkedHashMap<>(commonCondition());
		condition.put("rows", CONSUME_ROWS);
		condition.put("event_type", IngestionEventType.DETAIL_READY.name());
		condition.put("consumer", isolating ? "StreamConsumer(프로덕션 - 격리 후 확인)"
				: "NoDeadLetterConsumer(lab - 격리 없이 확인만)");
		condition.put("failure_injection", "핸들러가 항상 RETRY_EXHAUSTED");
		condition.put("threads", 1);
		condition.put("lock", "-");
		condition.put("gauge_sample_ms", GAUGE_SAMPLE_MS);
		raw.baseCondition(condition);

		labHandler.configure(FaultInjectingEventHandler.Mode.EXHAUSTED, 100, 0L,
				RetryLabConfig.reclaimFaultSeed());
		seedOutbox(CONSUME_ROWS);
		while (outboxDispatcher.dispatchPending() > 0) {
			// 적재분을 전부 스트림으로 내보낸다.
		}

		// 관측창 시작 - 적재는 창 밖이다. 카운터 스냅샷과 t0 은 같은 지점에서 떠야 대조가 성립한다.
		MetricProbe probe = new MetricProbe(meterRegistry);
		LocalDateTime windowStart = IngestionClock.now();
		MetricProbe.Snapshot before = probe.snapshot();
		MetricProbe.Sampler sampler = probe.sampleGauges(GAUGE_SAMPLE_MS);

		NoDeadLetterConsumer losing = new NoDeadLetterConsumer(eventRouter, redisTemplate, properties);
		int consumed = 0;
		for (List<MapRecord<String, String, String>> batch = read(); !batch.isEmpty(); batch = read()) {
			for (MapRecord<String, String, String> record : batch) {
				if (isolating) {
					streamConsumer.onMessage(record);
				} else {
					losing.onMessage(record);
				}
				consumed++;
			}
		}

		MetricProbe.Snapshot after = probe.snapshot();
		List<Map<String, Object>> gaugeSamples = sampler.stop();

		long isolated = countDeadLetters();
		Map<String, Object> reconciliation = new LinkedHashMap<>();
		reconciliation.put("consumed", consumed);
		reconciliation.put("handler_failures", labHandler.failureCount());
		reconciliation.put("isolated_rows", isolated);
		reconciliation.put("lost", isolating ? 0 : losing.lost().size());
		reconciliation.put("difference_isolated_minus_exhausted", isolated - labHandler.failureCount());
		reconciliation.put("pending_entries_left", pendingCount());
		reconciliation.put("stream_length", lengthOf(IngestionStream.CULTURE));
		raw.observation("isolation_reconciliation", reconciliation);
		recordMetrics(raw, probe, before, after, gaugeSamples, windowStart);
		raw.finish();
	}

	@Test
	@DisplayName("C. 격리 행을 되돌려 보내 배달 계층 회생(L1)을 잰다")
	void 시나리오C_재처리() {
		labHandler.configure(FaultInjectingEventHandler.Mode.EXHAUSTED, 100, 0L,
				RetryLabConfig.reclaimFaultSeed());
		seedOutbox(CONSUME_ROWS);
		while (outboxDispatcher.dispatchPending() > 0) {
			// 스트림으로 내보낸다.
		}

		// 관측창은 격리 단계부터 - 이 시나리오는 isolated 와 redrive 를 한 창에서 함께 대조한다.
		MetricProbe probe = new MetricProbe(meterRegistry);
		LocalDateTime windowStart = IngestionClock.now();
		MetricProbe.Snapshot before = probe.snapshot();
		MetricProbe.Sampler sampler = probe.sampleGauges(GAUGE_SAMPLE_MS);

		for (List<MapRecord<String, String, String>> batch = read(); !batch.isEmpty(); batch = read()) {
			batch.forEach(streamConsumer::onMessage);
		}

		RetryLabRaw raw = new RetryLabRaw(STEP, "scenario-C-redrive");
		Map<String, Object> condition = new LinkedHashMap<>(commonCondition());
		condition.put("rows", CONSUME_ROWS);
		condition.put("threads", 1);
		condition.put("lock", "-");
		condition.put("l1_definition", "격리 행 REPLAYED + 새 아웃박스 행 SENT + 핸들러 재실행 1회");
		condition.put("l2_definition", "도메인 스텝이 DONE 으로 돌아오는지 - 이 하네스의 관측 범위 밖(핸들러가 도메인이 아니다)");
		condition.put("gauge_sample_ms", GAUGE_SAMPLE_MS);
		raw.baseCondition(condition);

		List<Long> targets = jdbcTemplate.queryForList(
				"select id from ingestion_dead_letter where status = 'PENDING' order by id", Long.class);
		labHandler.configure(FaultInjectingEventHandler.Mode.HEALTHY, 0, 0L, 0L);

		int redriven = 0;
		List<Double> latencies = new ArrayList<>();
		for (long id : targets) {
			long started = System.nanoTime();
			ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(id));
			latencies.add((System.nanoTime() - started) / 1_000_000d);
			redriven++;
		}
		while (outboxDispatcher.dispatchPending() > 0) {
			// 되돌려 보낸 행을 다시 발송한다.
		}
		for (List<MapRecord<String, String, String>> batch = read(); !batch.isEmpty(); batch = read()) {
			batch.forEach(streamConsumer::onMessage);
		}

		MetricProbe.Snapshot after = probe.snapshot();
		List<Map<String, Object>> gaugeSamples = sampler.stop();

		raw.series("redrive_latency", "재주입 단건 지연", "ms", toArray(latencies), Map.of());
		Map<String, Object> l1 = new LinkedHashMap<>();
		l1.put("isolated_before_redrive", targets.size());
		l1.put("redrive_calls", redriven);
		l1.put("replayed_rows", countDeadLetterStatus("REPLAYED"));
		l1.put("handler_success_after_redrive", labHandler.successCount());
		l1.put("outbox_sent", countOutbox("SENT"));
		l1.put("pending_entries_left", pendingCount());
		l1.put("l1_success_rate", targets.isEmpty() ? null
				: (double) countDeadLetterStatus("REPLAYED") / targets.size());
		raw.observation("redrive_l1", l1);
		recordMetrics(raw, probe, before, after, gaugeSamples, windowStart);
		raw.note("L2(도메인 회생)는 이 하네스가 관측하지 않는다. 재주입은 아웃박스 행만 새로 넣고, "
				+ "도메인 스텝의 회생 경로는 관리자 reopen 이라는 사실을 코드에서 확인해 design-notes 에 남긴다.");
		raw.finish();
	}

	/**
	 * step-08 채취 - 카운터·타이머 델타, 계기 시계열, 대조 네 줄을 같은 run 에 남긴다.
	 *
	 * <ul>
	 *   <li>대조 대상 넷은 계획서 메트릭 표 5·6 행의 식 그대로다</li>
	 *   <li>합격 판정은 하지 않는다 - {@code difference} 만 적고 읽는 것은 실험자다</li>
	 *   <li>창 밖의 행이 섞이지 않는 근거는 {@code resetPipeline()} 이 매 run 앞에서 격리 테이블을 비운다는 것.
	 *       {@code failed_at}·{@code resolved_at} 은 {@code datetime(6)} 이라 절단으로 새는 행도 없다</li>
	 * </ul>
	 */
	private void recordMetrics(RetryLabRaw raw, MetricProbe probe, MetricProbe.Snapshot before,
			MetricProbe.Snapshot after, List<Map<String, Object>> gaugeSamples, LocalDateTime windowStart) {
		double isolatedExhausted = MetricProbe.counterDelta(before, after,
				DeadLetterService.ISOLATED_COUNTER, "reason", "exhausted");
		double isolatedMalformed = MetricProbe.counterDelta(before, after,
				DeadLetterService.ISOLATED_COUNTER, "reason", "malformed");
		double redriveSuccess = MetricProbe.counterDelta(before, after,
				DeadLetterService.REDRIVE_COUNTER, "result", "success");
		double redriveConflict = MetricProbe.counterDelta(before, after,
				DeadLetterService.REDRIVE_COUNTER, "result", "conflict");
		double redriveAlreadyResolved = MetricProbe.counterDelta(before, after,
				DeadLetterService.REDRIVE_COUNTER, "result", "already_resolved");

		long isolatedRows = countSince("select count(*) from ingestion_dead_letter where failed_at >= ?",
				windowStart);
		long replayedRows = countSince(
				"select count(*) from ingestion_dead_letter where status = 'REPLAYED' and resolved_at >= ?",
				windowStart);

		List<String> isolatedKeys = new ArrayList<>(MetricProbe.matchedKeys(before, after,
				DeadLetterService.ISOLATED_COUNTER, "reason", "exhausted"));
		isolatedKeys.addAll(MetricProbe.matchedKeys(before, after,
				DeadLetterService.ISOLATED_COUNTER, "reason", "malformed"));

		List<Map<String, Object>> reconciliations = List.of(
				MetricProbe.reconcile("ingestion.deadletter.isolated{reason=exhausted}+{reason=malformed}",
						isolatedExhausted + isolatedMalformed, isolatedRows,
						"select count(*) from ingestion_dead_letter where failed_at >= t0",
						isolatedKeys),
				MetricProbe.reconcile("ingestion.deadletter.redrive{result=success}",
						redriveSuccess, replayedRows,
						"select count(*) from ingestion_dead_letter where status='REPLAYED' and resolved_at >= t0",
						MetricProbe.matchedKeys(before, after,
								DeadLetterService.REDRIVE_COUNTER, "result", "success")));

		Map<String, Object> metrics = new LinkedHashMap<>(MetricProbe.delta(before, after));
		metrics.put("window_start", windowStart.toString());
		metrics.put("isolated_exhausted_delta", isolatedExhausted);
		metrics.put("isolated_malformed_delta", isolatedMalformed);
		metrics.put("redrive_success_delta", redriveSuccess);
		metrics.put("redrive_conflict_delta", redriveConflict);
		metrics.put("redrive_already_resolved_delta", redriveAlreadyResolved);
		metrics.put("dead_letter_rows_since_t0", isolatedRows);
		metrics.put("dead_letter_replayed_rows_since_t0", replayedRows);
		metrics.put("reconciliation", reconciliations);
		metrics.put("gauge_sample_ms", GAUGE_SAMPLE_MS);
		metrics.put("gauge_samples", gaugeSamples);
		metrics.put("gauge_note", "계기는 표본 주기보다 짧은 봉우리를 놓친다. peak 주장은 카운터 델타로 한다.");
		raw.observation("metrics", metrics);
		raw.attach("metrics-snapshot.json", "window_start=" + windowStart + "\n"
				+ "before=" + before.counters() + "\n"
				+ "after=" + after.counters() + "\n"
				+ "gauges_before=" + before.gauges() + "\n"
				+ "gauges_after=" + after.gauges() + "\n"
				+ "registry=" + probe.getClass().getSimpleName() + "(prefix=ingestion.)\n");
	}

	private long countSince(String sql, LocalDateTime from) {
		Long count = jdbcTemplate.queryForObject(sql, Long.class, Timestamp.valueOf(from));
		return count == null ? 0 : count;
	}

	private ToggleableEventDispatcher toggle() {
		return (ToggleableEventDispatcher) eventDispatcher;
	}

	private List<MapRecord<String, String, String>> read() {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		List<MapRecord<String, String, String>> records = streamOperations.read(
				Consumer.from(properties.consumerGroup(), properties.consumerName()),
				StreamReadOptions.empty().count(READ_BATCH),
				StreamOffset.create(IngestionStream.CULTURE.key(), ReadOffset.lastConsumed()));
		return records == null ? List.of() : records;
	}

	private long pendingCount() {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		PendingMessages pending = streamOperations.pending(IngestionStream.CULTURE.key(),
				properties.consumerGroup(), Range.unbounded(), 10_000L);
		return pending == null ? 0 : pending.size();
	}

	private long lengthOf(IngestionStream stream) {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		Long size = streamOperations.size(stream.key());
		return size == null ? 0L : size;
	}

	private long countOutbox(String status) {
		Long count = jdbcTemplate.queryForObject(
				"select count(*) from ingestion_outbox where status = ?", Long.class, status);
		return count == null ? 0 : count;
	}

	private long countDeadLetters() {
		Long count = jdbcTemplate.queryForObject("select count(*) from ingestion_dead_letter", Long.class);
		return count == null ? 0 : count;
	}

	private long countDeadLetterStatus(String status) {
		Long count = jdbcTemplate.queryForObject(
				"select count(*) from ingestion_dead_letter where status = ?", Long.class, status);
		return count == null ? 0 : count;
	}

	/** 아웃박스 시드 - 서비스 호출 1만 번 대신 배치 INSERT. payload 는 프로덕션과 같은 모양으로 만든다. */
	private void seedOutbox(int rows) {
		List<Object[]> batch = new ArrayList<>();
		for (int index = 0; index < rows; index++) {
			String aggregateId = "LAB05-" + index + "-" + System.nanoTime();
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

	private static double[] toArray(List<Double> values) {
		double[] array = new double[values.size()];
		for (int index = 0; index < values.size(); index++) {
			array[index] = values.get(index);
		}
		return array;
	}
}
