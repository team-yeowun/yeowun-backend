package modi.backend.ingestionv2.lab.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * step-07 교차점 탐색 - 선점 쿼리 p95 가 틱 예산(1,000ms)을 <b>처음 넘는 행수</b>를 찾는다.
 *
 * <ul>
 *   <li>규모 사다리 4점(S0·S1·S2·S3)은 곡선의 뼈대일 뿐 교차점을 짚지 못한다. 이 클래스는
 *       {@code lab.outbox.checkpoints} 가 주는 <b>임의 행수 목록</b>을 촘촘히 올라가며 같은 지표를 잰다</li>
 *   <li>기본 사다리: 25,000 -&gt; 100,000 -&gt; 200,000 -&gt; ... -&gt; 1,000,000 (10만 단위)</li>
 *   <li>적재는 누적이라 마지막 점까지 총 100만 행을 한 번만 쌓는다(점마다 truncate 하지 않는다)</li>
 *   <li>before(인덱스 없음) / after((status, created_at)) 두 조건 모두에서 재고, 각 조건의 최초 초과 행수를
 *       {@code crossover} 관측으로 남긴다. 끝까지 넘지 않으면 넘지 않았다고 그대로 적는다</li>
 *   <li>지표는 주 지표 하나(PENDING 1,000행, 원시 SQL 선점)로 한정한다 - 교차점의 정의가 그 값이다</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.outbox.OutboxCrossoverLabTest"}
 */
@Tag("manual")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxCrossoverLabTest extends OutboxLabSupport {

	private static final int LIMIT = 100;
	private static final String DEFAULT_CHECKPOINTS =
			"25000,100000,200000,300000,400000,500000,600000,700000,800000,900000,1000000";

	@AfterAll
	void restore() {
		closeLab();
	}

	@Test
	@DisplayName("step-07 — 행수를 올려가며 선점 쿼리 p95 가 틱 예산을 처음 넘는 지점을 찾는다")
	void 선점_쿼리_p95가_틱_예산을_처음_넘는_행수를_찾는다() {
		List<Long> checkpoints = checkpoints();
		double thresholdMillis = Double.parseDouble(OutboxLabConfig.get("crossoverThresholdMs", "1000"));
		prepareLab(checkpoints.get(checkpoints.size() - 1));

		OutboxLabRaw before = new OutboxLabRaw("step-07-crossover", "before");
		OutboxLabRaw after = new OutboxLabRaw("step-07-crossover", "after");

		Map<String, Object> base = baseCondition();
		base.put("claim_sql", CLAIM_SQL.formatted(LIMIT));
		base.put("checkpoints", checkpoints.toString());
		base.put("crossover_threshold_ms", thresholdMillis);
		base.put("crossover_threshold_source",
				"app.ingestion.v2.dispatch-interval-ms 기본 1000ms 예산(계획서 C1 의 after 합격 기준과 같은 값)");
		base.put("metric_scope", "PENDING 1,000행 / 원시 SQL 선점 하나만. pending 0행·프로덕션 경로는 step-01 이 맡는다");
		base.put("created_at_anchor", String.valueOf(seeder.anchor()));
		before.baseCondition(base);
		after.baseCondition(base);
		before.attach("scheduler-beans.txt", schedulerBeanReport());
		after.attach("scheduler-beans.txt", schedulerBeanReport());
		assertThat(schedulersUnregistered())
				.as("auto-delivery=false 인데 스케줄러 빈이 떠 있으면 측정이 오염된다")
				.isTrue();

		List<Map<String, Object>> beforeCurve = new ArrayList<>();
		List<Map<String, Object>> afterCurve = new ArrayList<>();

		for (long rows : checkpoints) {
			OutboxLabSeeder.RowsSeedResult seed = seeder.ensureRows(rows);
			Map<String, Object> checkpointCondition = new LinkedHashMap<>(seed.toMap());
			checkpointCondition.putAll(tableStats());
			checkpointCondition.put("status_counts", seeder.countByStatus());
			checkpointCondition.put("limit", LIMIT);

			// ── before: 인덱스 제거 ──────────────────────────────────────────────
			before.attach("show-index-%d-1-before-drop.txt".formatted(rows), indexSwitch.showIndex());
			double dropMillis = indexSwitch.dropCurrent();
			jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
			before.attach("show-index-%d-2-after-drop.txt".formatted(rows), indexSwitch.showIndex());
			Map<String, Object> beforeCondition = new LinkedHashMap<>(checkpointCondition);
			beforeCondition.put("index_ddl_ms", OutboxLabStats.round(dropMillis));
			double[] beforeValues = repeat(() -> claimOnceMillis(CLAIM_SQL, LIMIT));
			before.series("rows%d-pending1000-raw-sql".formatted(rows), "claim_query_latency_ms", "ms",
					beforeValues, beforeCondition);
			beforeCurve.add(point(rows, OutboxLabStats.of(beforeValues), thresholdMillis));

			// ── after: 인덱스 복구 ───────────────────────────────────────────────
			double createMillis = indexSwitch.createCurrent();
			jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
			after.attach("show-index-%d-3-after-create.txt".formatted(rows), indexSwitch.showIndex());
			Map<String, Object> afterCondition = new LinkedHashMap<>(checkpointCondition);
			afterCondition.put("index_ddl_ms", OutboxLabStats.round(createMillis));
			double[] afterValues = repeat(() -> claimOnceMillis(CLAIM_SQL, LIMIT));
			after.series("rows%d-pending1000-raw-sql".formatted(rows), "claim_query_latency_ms", "ms",
					afterValues, afterCondition);
			afterCurve.add(point(rows, OutboxLabStats.of(afterValues), thresholdMillis));
		}

		before.observation("crossover", crossover("before", beforeCurve, thresholdMillis));
		after.observation("crossover", crossover("after", afterCurve, thresholdMillis));
		before.finish();
		after.finish();

		assertThat(before.directory().resolve("run-summary.json")).exists();
		assertThat(after.directory().resolve("run-summary.json")).exists();
		assertThat(indexSwitch.exists(OutboxLabIndexSwitch.CURRENT_INDEX))
				.as("측정이 끝나면 현행 인덱스가 되돌아와 있어야 한다")
				.isTrue();
	}

	/** 체크포인트 목록. 쉼표로 구분한 임의 행수를 오름차순으로 정리한다. */
	private static List<Long> checkpoints() {
		List<Long> rows = new ArrayList<>();
		for (String token : OutboxLabConfig.get("checkpoints", DEFAULT_CHECKPOINTS).split(",")) {
			String trimmed = token.trim().toLowerCase(Locale.ROOT).replace("_", "");
			if (!trimmed.isEmpty()) {
				rows.add(Long.parseLong(trimmed));
			}
		}
		return rows.stream().distinct().sorted().toList();
	}

	private static Map<String, Object> point(long rows, OutboxLabStats stats, double thresholdMillis) {
		Map<String, Object> point = new LinkedHashMap<>();
		point.put("rows", rows);
		point.put("p50", OutboxLabStats.round(stats.p50()));
		point.put("p95", OutboxLabStats.round(stats.p95()));
		point.put("max", OutboxLabStats.round(stats.max()));
		point.put("exceeds_threshold", stats.p95() > thresholdMillis);
		return point;
	}

	/** 최초 초과 행수와 그 앞뒤 구간. 끝까지 넘지 않았으면 그 사실을 그대로 적는다. */
	private static Map<String, Object> crossover(String variant, List<Map<String, Object>> curve,
			double thresholdMillis) {
		Map<String, Object> previous = null;
		Map<String, Object> first = null;
		for (Map<String, Object> point : curve) {
			if (Boolean.TRUE.equals(point.get("exceeds_threshold"))) {
				first = point;
				break;
			}
			previous = point;
		}
		Map<String, Object> observation = new LinkedHashMap<>();
		observation.put("variant", variant);
		observation.put("threshold_ms", thresholdMillis);
		observation.put("metric", "claim_query_latency_ms p95 (PENDING 1,000행, 원시 SQL)");
		observation.put("first_exceeding_rows", first == null ? null : first.get("rows"));
		observation.put("first_exceeding_p95", first == null ? null : first.get("p95"));
		observation.put("last_below_rows", previous == null ? null : previous.get("rows"));
		observation.put("last_below_p95", previous == null ? null : previous.get("p95"));
		observation.put("crossed", first != null);
		observation.put("note", first == null
				? "측정한 마지막 체크포인트까지 p95 가 예산을 넘지 않았다. 교차점은 이 범위 밖이며 외삽하지 않는다."
				: "교차점은 %s행과 %s행 사이에 있다. 두 점 사이는 재지 않았으므로 구간으로만 말한다."
						.formatted(previous == null ? "(첫 점 이전)" : previous.get("rows"), first.get("rows")));
		observation.put("curve", curve);
		return observation;
	}
}
