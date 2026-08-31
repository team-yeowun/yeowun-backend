package modi.backend.ingestionv2.lab.retry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.queue.IngestionStream;

/**
 * step-07 재처리 동시성 - 격리 행 하나에 스레드 여럿이 동시에 재주입을 건다.
 *
 * <ul>
 *   <li>변형 하나 x 스레드 수 하나가 폴더 하나 - {@code step-07/{변형}-t{스레드}/}</li>
 *   <li>한 run = 격리 행 {@code rows} 개, 행마다 {@code threads} 개 스레드가 같은 latch 로 동시 출발</li>
 *   <li>성공 지연과 실패 지연을 <b>섞지 않는다</b> - 대기형(V2)과 즉시형(V1·V3)을 가르는 것이 실패 쪽 지연이다</li>
 *   <li>정확성 두 지표(행당 성공 건수·중복 아웃박스 행)는 매 run 전수 확인 - 표본이 아니다</li>
 *   <li>Phase A(P0)는 {@code @Version} 마이그레이션 이전 커밋에서만 성립하므로 before 진위 확인에
 *       {@code show columns ... like 'version'} 출력을 그대로 남긴다</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.retry.RedriveConcurrencyLab"}
 * (변형 선택은 {@code lab-retry.properties} 의 {@code lab.retry.redrive.variants})
 */
@DisplayName("[lab] step-07 재처리 동시성")
class RedriveConcurrencyLab extends RetryLabSupport {

	private static final String STEP = "step-07";

	/** 격리 시드가 쓰는 이벤트 종류 - 스트림 배정이 있는 타입이라야 재주입 결과가 실제 발행 경로를 탄다(F-12). */
	private static final IngestionEventType SEED_EVENT_TYPE = IngestionEventType.DETAIL_READY;

	@Test
	@DisplayName("변형별로 행당 성공 건수·중복 적재·지연·잠금 대기를 잰다")
	void 재처리_동시성_변형별_측정() {
		RedriveRunner runner = new RedriveRunner(ingestionDeliveryFacade, deadLetterService, outboxService,
				transactionTemplate, entityManagerFactory);
		for (RedriveVariant variant : RetryLabConfig.redriveVariants()) {
			for (int threads : RetryLabConfig.redriveThreads()) {
				measure(runner, variant, threads);
			}
		}
	}

	private void measure(RedriveRunner runner, RedriveVariant variant, int threads) {
		int rows = RetryLabConfig.redriveRows();
		int warmup = RetryLabConfig.warmup();
		int runs = RetryLabConfig.runs();

		RetryLabRaw raw = new RetryLabRaw(STEP, variant.name() + "-t" + threads);
		Map<String, Object> condition = new LinkedHashMap<>(commonCondition());
		condition.put("variant", variant.name());
		condition.put("phase", variant.phase());
		condition.put("lock", variant.lock());
		condition.put("tx_boundary", variant.txBoundary());
		condition.put("location", variant.location());
		condition.put("on_locked_row", variant.onLockedRow());
		condition.put("rows", rows);
		condition.put("threads", threads);
		condition.put("requests_per_run", rows * threads);
		condition.put("event_type", SEED_EVENT_TYPE.name());
		condition.put("before_check", "show columns from ingestion_dead_letter like 'version' -> "
				+ versionColumnCheck());
		condition.put("before_note", "before(P0)는 과거의 코드가 아니라 현행 프로덕션 파사드(3-tx·무락)다. "
				+ "이 실험은 사건 재현이 아니라 락 방식 네 갈래의 설계 비교다.");
		raw.baseCondition(condition);
		raw.note("실험 범위 축소 - 변형은 P0·V1·V2·V3 넷, 스레드는 " + RetryLabConfig.redriveThreads() + " 만 잰다.");
		raw.note("성공 지연과 실패 지연은 별도 계열이다. 한 배열로 합치면 대기형과 즉시형의 차이가 지워진다.");
		if (variant.usesProductionFacade()) {
			raw.note("3-tx 변형은 tx 지속시간을 분해 측정하지 않는다(파사드 내부). 요청 지연이 상한이다.");
		}

		List<Double> successP50 = new ArrayList<>();
		List<Double> successP95 = new ArrayList<>();
		List<Double> failureP50 = new ArrayList<>();
		List<Double> failureP95 = new ArrayList<>();
		List<Double> failureMax = new ArrayList<>();
		List<Double> txP95 = new ArrayList<>();
		List<Double> duplicates = new ArrayList<>();
		List<Double> multiSuccessRows = new ArrayList<>();
		List<Double> lockWaitDeltas = new ArrayList<>();
		List<Double> lockTimeDeltas = new ArrayList<>();
		Map<String, Integer> outcomeTypes = new TreeMap<>();
		Map<Integer, Integer> successHistogram = new TreeMap<>();
		Map<String, Object> lastLockSummary = Map.of();
		String lastLockSamples = "";

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int index = 0; index < warmup + runs; index++) {
				boolean measured = index >= warmup;
				RunResult result = runOnce(runner, pool, variant, rows, threads, index);
				if (!measured) {
					continue;
				}
				successP50.add(RetryLabStats.of(result.successLatencies()).p50());
				successP95.add(RetryLabStats.of(result.successLatencies()).p95());
				RetryLabStats failure = RetryLabStats.of(result.failureLatencies());
				failureP50.add(failure.p50());
				failureP95.add(failure.p95());
				failureMax.add(failure.max());
				txP95.add(RetryLabStats.of(result.txDurations()).p95());
				duplicates.add((double) result.duplicateOutboxRows());
				multiSuccessRows.add((double) result.rowsWithMultipleSuccess());
				lockWaitDeltas.add(asDouble(result.lockSummary().get("Innodb_row_lock_waits_delta")));
				lockTimeDeltas.add(asDouble(result.lockSummary().get("Innodb_row_lock_time_delta")));
				result.outcomeTypes().forEach((type, count) -> outcomeTypes.merge(type, count, Integer::sum));
				result.successHistogram().forEach((count, rowsWith) ->
						successHistogram.merge(count, rowsWith, Integer::sum));
				lastLockSummary = result.lockSummary();
				lastLockSamples = result.lockSamples();
			}
		} finally {
			pool.shutdownNow();
		}

		Map<String, Object> empty = Map.of();
		raw.series("success_latency_p50", "성공 요청 응답 지연 p50", "ms", toArray(successP50), empty);
		raw.series("success_latency_p95", "성공 요청 응답 지연 p95", "ms", toArray(successP95), empty);
		raw.series("failure_latency_p50", "실패 요청 응답 지연 p50", "ms", toArray(failureP50), empty);
		raw.series("failure_latency_p95", "실패 요청 응답 지연 p95", "ms", toArray(failureP95), empty);
		raw.series("failure_latency_max", "실패 요청 응답 지연 max", "ms", toArray(failureMax), empty);
		if (!variant.usesProductionFacade()) {
			raw.series("tx_duration_p95", "요청당 tx 지속시간 p95", "ms", toArray(txP95), empty);
		}
		raw.series("duplicate_outbox_rows", "중복 적재 아웃박스 행 수", "건", toArray(duplicates), empty);
		raw.series("rows_with_multiple_success", "성공이 2건 이상인 행 수", "건", toArray(multiSuccessRows), empty);
		raw.series("innodb_row_lock_waits_delta", "행 잠금 대기 횟수 델타", "회", toArray(lockWaitDeltas), empty);
		raw.series("innodb_row_lock_time_delta", "행 잠금 대기 시간 델타", "ms", toArray(lockTimeDeltas), empty);

		raw.observation("outcome_types", Map.of(
				"description", "실패 응답 유형 분포(본측정 run 합계). SUCCESS 는 성공 건수다.",
				"counts", outcomeTypes));
		raw.observation("success_per_row_histogram", Map.of(
				"description", "행당 성공 건수 분포(본측정 run 합계). key=성공 건수, value=그런 행의 수.",
				"histogram", successHistogram,
				"expectation", "락이 있는 변형은 key 가 1 하나여야 한다."));
		raw.observation("lock_status_last_run", Map.of(
				"description", "마지막 본측정 run 의 SHOW GLOBAL STATUS 델타·수준값",
				"values", lastLockSummary));
		raw.attach("lock-waits.txt", lastLockSamples);
		raw.attach("before-check.txt",
				"git rev-parse --short HEAD -> " + gitCommit() + "\n"
						+ "show columns from ingestion_dead_letter like 'version' -> " + versionColumnCheck() + "\n"
						+ "variant=" + variant.name() + " phase=" + variant.phase() + " lock=" + variant.lock()
						+ " tx=" + variant.txBoundary() + "\n");
		raw.finish();
	}

	private RunResult runOnce(RedriveRunner runner, ExecutorService pool, RedriveVariant variant,
			int rows, int threads, int runIndex) {
		clearSliceTables();
		List<Long> deadLetterIds = seedDeadLetters(rows, runIndex);

		LockWaitProbe probe = new LockWaitProbe(jdbcTemplate, RetryLabConfig.lockSampleIntervalMs());
		probe.start();

		List<Double> successLatencies = new ArrayList<>();
		List<Double> failureLatencies = new ArrayList<>();
		List<Double> txDurations = new ArrayList<>();
		Map<String, Integer> outcomeTypes = new TreeMap<>();
		Map<Integer, Integer> successHistogram = new TreeMap<>();

		for (long deadLetterId : deadLetterIds) {
			List<RedriveOutcome> outcomes = raceOn(runner, pool, variant, deadLetterId, threads);
			AtomicInteger successes = new AtomicInteger();
			for (RedriveOutcome outcome : outcomes) {
				outcomeTypes.merge(outcome.outcome(), 1, Integer::sum);
				if (outcome.success()) {
					successes.incrementAndGet();
					successLatencies.add(outcome.requestMillis());
				} else {
					failureLatencies.add(outcome.requestMillis());
				}
				if (outcome.txNanos() != RedriveRunner.TX_NOT_MEASURED) {
					txDurations.add(outcome.txMillis());
				}
			}
			successHistogram.merge(successes.get(), 1, Integer::sum);
		}

		probe.stop();
		return new RunResult(toArray(successLatencies), toArray(failureLatencies), toArray(txDurations),
				outcomeTypes, successHistogram, duplicateOutboxRows(), rowsWithMultipleSuccess(successHistogram),
				probe.summary(), probe.rawSamples());
	}

	/** 행 하나에 스레드 전부가 같은 latch 로 동시 출발한다. sleep 으로 경합 창을 벌리지 않는다. */
	private List<RedriveOutcome> raceOn(RedriveRunner runner, ExecutorService pool, RedriveVariant variant,
			long deadLetterId, int threads) {
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		List<RedriveOutcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
		for (int index = 0; index < threads; index++) {
			pool.execute(() -> {
				ready.countDown();
				try {
					start.await();
					outcomes.add(runner.redrive(variant, deadLetterId));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		await(ready);
		start.countDown();
		await(done);
		return List.copyOf(outcomes);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(120, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 출발 대기가 끝나지 않았습니다. 커넥션 풀 고갈을 의심하십시오.");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시 출발 대기가 끊겼습니다.", interrupted);
		}
	}

	/** 격리 행 시드 - version 컬럼을 명시하지 않아 Phase A/B 양쪽에서 같은 문장이 돈다. */
	private List<Long> seedDeadLetters(int rows, int runIndex) {
		List<Object[]> batch = new ArrayList<>();
		for (int index = 0; index < rows; index++) {
			String aggregateId = "LAB07-%d-%d-%d".formatted(runIndex, index, System.nanoTime());
			OutboxPayload payload = OutboxPayload.of(SEED_EVENT_TYPE, aggregateId, IngestionClock.now());
			batch.add(new Object[] {
					SEED_EVENT_TYPE.aggregateType().name(), aggregateId, SEED_EVENT_TYPE.name(), payload.toJson(),
					IngestionStream.CULTURE.key(), runIndex + "-" + index, "lab seed", null, "RedriveConcurrencyLab",
					properties.maxAttempts(), IngestionClock.now(), "PENDING"});
		}
		jdbcTemplate.batchUpdate("""
				insert into ingestion_dead_letter
				  (aggregate_type, aggregate_id, event_type, payload, stream_key, record_id,
				   error_message, stack_trace, failed_step, retry_count, failed_at, status)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", batch);
		return jdbcTemplate.queryForList("select id from ingestion_dead_letter order by id", Long.class);
	}

	/** 같은 좌표로 아웃박스 행이 두 번 이상 들어갔는지 - 중복 발송의 직접 증거. */
	private int duplicateOutboxRows() {
		Integer duplicates = jdbcTemplate.queryForObject("""
				select coalesce(sum(appended - 1), 0) from (
				    select count(*) as appended from ingestion_outbox group by aggregate_id
				) counted where appended > 1
				""", Integer.class);
		return duplicates == null ? 0 : duplicates;
	}

	private static int rowsWithMultipleSuccess(Map<Integer, Integer> histogram) {
		return histogram.entrySet().stream()
				.filter(entry -> entry.getKey() > 1)
				.mapToInt(Map.Entry::getValue)
				.sum();
	}

	private static double asDouble(Object value) {
		return value instanceof Number number ? number.doubleValue() : 0d;
	}

	private static double[] toArray(List<Double> values) {
		double[] array = new double[values.size()];
		for (int index = 0; index < values.size(); index++) {
			array[index] = values.get(index);
		}
		return array;
	}

	/** run 한 번의 산출물. 지연은 성공·실패로 갈라 담는다. */
	private record RunResult(double[] successLatencies, double[] failureLatencies, double[] txDurations,
			Map<String, Integer> outcomeTypes, Map<Integer, Integer> successHistogram,
			int duplicateOutboxRows, int rowsWithMultipleSuccess,
			Map<String, Object> lockSummary, String lockSamples) {
	}
}
