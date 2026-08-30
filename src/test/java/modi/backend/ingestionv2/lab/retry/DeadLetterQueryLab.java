package modi.backend.ingestionv2.lab.retry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionDeliveryCriteria;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.queue.IngestionStream;

/**
 * step-06 격리 목록의 관리 조회 - 인덱스 유무를 바꿔 가며 재는 A 방식(스키마 조작) 실험.
 *
 * <ul>
 *   <li>인덱스 복원을 {@code try/finally} 로 강제한다 - {@code ddl-auto=validate} 라 Hibernate 가 되살려 주지
 *       않고 컨테이너는 스위트 전체가 공유한다. 복원에 실패하면 뒤따르는 모든 측정이 오염된다(게이트 F-15)</li>
 *   <li>복원 뒤 {@code SHOW INDEX} 출력을 첨부한다 - 첨부가 없는 run 은 무효다</li>
 *   <li>규모 2점(1만·10만)을 재는 이유는 한 점 측정이 외삽 근거가 못 되기 때문이다</li>
 *   <li>{@code EXPLAIN ANALYZE} 전문을 조건마다 남긴다 - 지연 숫자보다 접근 경로가 더 오래 가는 증거다</li>
 *   <li>재주입 단건 지연도 같이 잰다 - 목록이 무거워질 때 쓰기 경로도 같이 느려지는지 보기 위함</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.retry.DeadLetterQueryLab"}
 */
@DisplayName("[lab] step-06 격리 목록 조회")
class DeadLetterQueryLab extends RetryLabSupport {

	private static final String STEP = "step-06";
	private static final String INDEX_NAME = "idx_ingestion_dead_letter_status_failed_at";
	private static final int SEED_CHUNK = 1_000;

	/** Spring Data 파생 쿼리 {@code findByStatusOrderByFailedAtAscIdAsc} 와 같은 모양. EXPLAIN 대상. */
	private static final String LIST_SQL = """
			select * from ingestion_dead_letter
			 where status = 'PENDING'
			 order by failed_at asc, id asc
			 limit %d
			""";

	@Test
	@DisplayName("규모 x 인덱스 유무 x 상한 별로 목록 조회 지연과 접근 경로를 남긴다")
	void 격리_목록_조회_측정() {
		for (int scale : RetryLabConfig.deadLetterScales()) {
			measureScale(scale);
		}
	}

	private void measureScale(int scale) {
		clearSliceTables();
		long seedStarted = System.nanoTime();
		seedDeadLetters(scale);
		double seedMillis = (System.nanoTime() - seedStarted) / 1_000_000d;

		List<Boolean> indexStates = RetryLabConfig.deadLetterMeasureWithoutIndex()
				? List.of(true, false)
				: List.of(true);

		try {
			for (boolean withIndex : indexStates) {
				applyIndex(withIndex);
				measureIndexState(scale, withIndex, seedMillis);
			}
		} finally {
			// 인덱스 복원은 어떤 경우에도 실행한다. 공유 컨테이너를 다음 측정에 그대로 넘기기 위함.
			applyIndex(true);
		}
	}

	private void measureIndexState(int scale, boolean withIndex, double seedMillis) {
		String variant = (withIndex ? "with-index" : "no-index") + "-" + scale;
		RetryLabRaw raw = new RetryLabRaw(STEP, variant);

		Map<String, Object> condition = new LinkedHashMap<>(commonCondition());
		condition.put("rows", scale);
		condition.put("index", withIndex ? INDEX_NAME : "(없음 - drop 후 측정)");
		condition.put("repeats_per_run", RetryLabConfig.deadLetterRepeats());
		condition.put("seed_millis", RetryLabStats.round(seedMillis));
		condition.put("status_mix", "PENDING 90% / REPLAYED 5% / IGNORED 5%");
		condition.put("failed_at_spread", "최근 30일 분산");
		condition.put("before_check", "show index -> " + showIndex());
		condition.put("threads", 1);
		condition.put("lock", "-");
		raw.baseCondition(condition);
		raw.note("인덱스 복원은 try/finally 로 강제하고 복원 후 SHOW INDEX 를 첨부한다(F-15).");

		for (int limit : RetryLabConfig.deadLetterLimits()) {
			measureLimit(raw, limit);
			raw.attach("explain-limit-" + limit + (withIndex ? "-with-index" : "-no-index") + ".txt",
					explainAnalyze(limit));
		}
		measureRedrive(raw);

		raw.attach("show-index-after.txt", showIndex());
		raw.finish();
	}

	private void measureLimit(RetryLabRaw raw, int limit) {
		int repeats = RetryLabConfig.deadLetterRepeats();
		List<Double> perRunP50 = new ArrayList<>();
		List<Double> perRunP95 = new ArrayList<>();
		for (int index = 0; index < RetryLabConfig.warmup() + RetryLabConfig.runs(); index++) {
			double[] samples = new double[repeats];
			for (int repeat = 0; repeat < repeats; repeat++) {
				long started = System.nanoTime();
				deadLetterService.findPending(limit);
				samples[repeat] = (System.nanoTime() - started) / 1_000_000d;
			}
			if (index < RetryLabConfig.warmup()) {
				continue;
			}
			RetryLabStats stats = RetryLabStats.of(samples);
			perRunP50.add(stats.p50());
			perRunP95.add(stats.p95());
		}
		Map<String, Object> extra = Map.of("limit", limit);
		raw.series("list_latency_p50_limit" + limit, "격리 목록 조회 p50", "ms", toArray(perRunP50), extra);
		raw.series("list_latency_p95_limit" + limit, "격리 목록 조회 p95", "ms", toArray(perRunP95), extra);
	}

	/** 재주입 단건 - 목록과 같은 테이블에 쓰기가 얹힐 때의 지연. */
	private void measureRedrive(RetryLabRaw raw) {
		int repeats = RetryLabConfig.deadLetterRepeats();
		List<Long> targets = jdbcTemplate.queryForList("""
				select id from ingestion_dead_letter
				 where status = 'PENDING' and event_type is not null
				 order by id limit ?
				""", Long.class, repeats);
		if (targets.isEmpty()) {
			raw.note("재주입 대상이 없어 단건 지연을 재지 못했습니다.");
			return;
		}
		double[] samples = new double[targets.size()];
		for (int index = 0; index < targets.size(); index++) {
			long started = System.nanoTime();
			ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(targets.get(index)));
			samples[index] = (System.nanoTime() - started) / 1_000_000d;
		}
		raw.series("redrive_latency", "재주입 단건 지연", "ms", samples, Map.of("calls", targets.size()));
		raw.note("재주입은 상태를 REPLAYED 로 바꾸므로 이 계열은 run 반복이 아니라 단일 표본 " + targets.size() + "건이다.");
	}

	private void applyIndex(boolean withIndex) {
		boolean present = !jdbcTemplate.queryForList(
				"show index from ingestion_dead_letter where key_name = ?", INDEX_NAME).isEmpty();
		if (withIndex && !present) {
			jdbcTemplate.execute("create index " + INDEX_NAME
					+ " on ingestion_dead_letter (status, failed_at)");
		}
		if (!withIndex && present) {
			jdbcTemplate.execute("drop index " + INDEX_NAME + " on ingestion_dead_letter");
		}
	}

	private String showIndex() {
		return jdbcTemplate.queryForList("show index from ingestion_dead_letter").toString();
	}

	private String explainAnalyze(int limit) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"explain analyze " + LIST_SQL.formatted(limit));
		StringBuilder text = new StringBuilder();
		text.append("-- SQL\n").append(LIST_SQL.formatted(limit)).append("\n-- EXPLAIN ANALYZE\n");
		rows.forEach(row -> row.values().forEach(value -> text.append(value).append("\n")));
		return text.toString();
	}

	/** 격리 행 시드 - status 혼합과 failed_at 분산을 계획서 값 그대로. */
	private void seedDeadLetters(int rows) {
		List<Object[]> chunk = new ArrayList<>(SEED_CHUNK);
		for (int index = 0; index < rows; index++) {
			String status = index % 20 == 0 ? "REPLAYED" : (index % 20 == 1 ? "IGNORED" : "PENDING");
			String aggregateId = "LAB06-" + index;
			OutboxPayload payload = OutboxPayload.of(IngestionEventType.DETAIL_READY, aggregateId,
					IngestionClock.now());
			chunk.add(new Object[] {
					IngestionEventType.DETAIL_READY.aggregateType().name(), aggregateId,
					IngestionEventType.DETAIL_READY.name(), payload.toJson(),
					IngestionStream.CULTURE.key(), "0-" + index, "lab seed", null, "DeadLetterQueryLab",
					properties.maxAttempts(),
					IngestionClock.now().minusMinutes(index % (30 * 24 * 60)), status});
			if (chunk.size() == SEED_CHUNK) {
				flush(chunk);
			}
		}
		if (!chunk.isEmpty()) {
			flush(chunk);
		}
	}

	private void flush(List<Object[]> chunk) {
		jdbcTemplate.batchUpdate("""
				insert into ingestion_dead_letter
				  (aggregate_type, aggregate_id, event_type, payload, stream_key, record_id,
				   error_message, stack_trace, failed_step, retry_count, failed_at, status)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", chunk);
		chunk.clear();
	}

	private static double[] toArray(List<Double> values) {
		double[] array = new double[values.size()];
		for (int index = 0; index < values.size(); index++) {
			array[index] = values.get(index);
		}
		return array;
	}
}
