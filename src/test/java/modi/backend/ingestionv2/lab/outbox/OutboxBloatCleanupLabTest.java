package modi.backend.ingestionv2.lab.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * step-03(비대화 곡선 + 정리 배치 판정) 측정 하네스.
 *
 * <ul>
 *   <li>① 행수 곡선은 <b>재측정하지 않는다</b> - step-01 의 규모별 원시값을 그대로 쓴다(계획서 F-02)</li>
 *   <li>② 정리 1회 실측: {@code OutboxService.cleanupSent(now - retentionDays, cleanupBatchSize)} 를 3회
 *       호출해 삭제 행수와 소요 시간을 남긴다. 이것이 현행 스케줄러의 하루치 작업량이다</li>
 *   <li>③ 산술 판정: 일일 유입 상한(3,500 = max-items 500 x 이벤트 7종) vs 일일 삭제 상한(cron 1회 x 500)</li>
 *   <li>N=3 이므로 문서에는 max·median 을 쓰고 백분위 표기는 {@code p95(N=3 -> 최댓값과 동일)} 로 병기한다</li>
 *   <li>합격 기준(일일 삭제 >= 일일 유입) 미달이어도 그대로 기록한다 - 결과를 숨기지 않는다</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.outbox.*"}
 */
@Tag("manual")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxBloatCleanupLabTest extends OutboxLabSupport {

	private static final int CLEANUP_CALLS = 3;

	@AfterAll
	void restore() {
		closeLab();
	}

	@Test
	@DisplayName("step-03 — 정리 배치 1회의 삭제 행수·소요 시간을 재고 일일 유입과 대조한다")
	void 정리_배치_한_번의_작업량을_재고_일일_유입과_대조한다() {
		List<OutboxLabScale> scales = OutboxLabConfig.scales();
		OutboxLabScale scale = scales.get(scales.size() - 1);
		prepareLab(scale.totalRows());

		OutboxLabRaw before = new OutboxLabRaw("step-03", "before");
		OutboxLabRaw after = new OutboxLabRaw("step-03", "after");
		Map<String, Object> base = baseCondition();
		base.put("scale_ladder", scales.toString());
		before.baseCondition(base);
		after.baseCondition(base);
		after.attach("scheduler-beans.txt", schedulerBeanReport());
		assertThat(schedulersUnregistered()).isTrue();

		// ── ① 곡선: 재측정 없음. 어디를 봐야 하는지만 남긴다 ──────────────────────
		before.note("① 행수 곡선은 재측정하지 않는다. 원시값은 step-01/{before,after}/run-summary.json 의 "
				+ "규모별 계열(S0·S1·S2[·S3] x PENDING 1,000·0)을 그대로 쓴다(계획서 F-02).");
		before.observation("bloat-curve-source", Map.of(
				"reused_from", "../../step-01/before/run-summary.json",
				"reused_from_after", "../../step-01/after/run-summary.json",
				"interpretation", "정리가 유입을 따라가면 테이블은 S0(보존 7일치)에 머물고, 따라가지 못하면 S1·S2로 이동한다. "
						+ "인덱스가 없으면 그 이동이 곧 지연 증가고, 있으면 지연은 평평하되 data_length·index_length가 자란다."));
		before.finish();

		// ── ② 정리 1회 실측 3회 ────────────────────────────────────────────────
		OutboxLabSeeder.SeedResult seed = seeder.ensureScale(scale);
		Map<String, Object> scaleCondition = scaleCondition(seed);
		LocalDateTime threshold = retentionThreshold();
		long eligible = countSentBefore(threshold);

		double[] elapsed = new double[CLEANUP_CALLS];
		List<Integer> deleted = new ArrayList<>();
		long rowsBefore = seeder.count();
		for (int call = 0; call < CLEANUP_CALLS; call++) {
			long startedAt = System.nanoTime();
			int rows = outboxService.cleanupSent(threshold, properties.cleanupBatchSize());
			elapsed[call] = (System.nanoTime() - startedAt) / 1_000_000d;
			deleted.add(rows);
		}
		long rowsAfter = seeder.count();

		Map<String, Object> cleanupCondition = new LinkedHashMap<>(scaleCondition);
		cleanupCondition.put("pending_rows", OutboxLabScale.PENDING_ROWS);
		cleanupCondition.put("retention_threshold", threshold.toString());
		cleanupCondition.put("sent_rows_eligible", eligible);
		cleanupCondition.put("n_note", "N=%d -> p95(N=%d -> 최댓값과 동일)".formatted(CLEANUP_CALLS, CLEANUP_CALLS));
		after.series("%s-cleanup-once".formatted(scale), "cleanup_batch_latency_ms", "ms", elapsed, cleanupCondition);

		Map<String, Object> deletion = new LinkedHashMap<>(cleanupCondition);
		deletion.put("deleted_rows_per_call", deleted);
		deletion.put("rows_before", rowsBefore);
		deletion.put("rows_after", rowsAfter);
		deletion.put("rows_removed_total", rowsBefore - rowsAfter);
		after.observation("cleanup-deleted-rows", deletion);

		// ── ③ 산술 판정 ────────────────────────────────────────────────────────
		long dailyInflow = OutboxLabScale.ROWS_PER_DAY;
		long dailyDelete = (long) properties.cleanupBatchSize();
		long netGrowth = dailyInflow - dailyDelete;
		Map<String, Object> arithmetic = new LinkedHashMap<>();
		arithmetic.put("daily_inflow_rows", dailyInflow);
		arithmetic.put("daily_inflow_formula", "catalog.max-items(500) x IngestionEventType 7종");
		arithmetic.put("daily_delete_rows", dailyDelete);
		arithmetic.put("daily_delete_formula", "cleanup-cron 1일 1회 x cleanup-batch-size(%d)".formatted(dailyDelete));
		arithmetic.put("net_daily_growth_rows", netGrowth);
		arithmetic.put("pass_criterion", "일일 삭제 >= 일일 유입");
		arithmetic.put("passed", dailyDelete >= dailyInflow);
		arithmetic.put("days_to_drain_current_table",
				dailyDelete <= 0 ? null : (double) rowsAfter / dailyDelete);
		arithmetic.put("days_to_drain_note",
				"유입이 멈춘다는 가정에서의 값. 유입이 계속되면 현행 설정으로는 비워지지 않는다.");
		arithmetic.put("cleanup_scope_limit", "cleanupSent 는 SENT 만 지운다. FAILED 는 남는다.");
		after.observation("cleanup-vs-inflow-arithmetic", arithmetic);

		after.finish();
		assertThat(after.directory().resolve("run-summary.json")).exists();
	}

	private long countSentBefore(LocalDateTime threshold) {
		Long rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ingestion_outbox WHERE status = 'SENT' AND created_at < ?",
				Long.class, threshold);
		return rows == null ? 0L : rows;
	}
}
