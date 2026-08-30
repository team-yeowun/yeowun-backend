package modi.backend.ingestionv2.lab.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * step-01(선점 쿼리 지연 곡선) · step-02(실행 계획 원문) 측정 하네스.
 *
 * <ul>
 *   <li>before 복원은 프로토콜 §2-A - {@code DROP INDEX idx_ingestion_outbox_status_created} 로
 *       PK 만 남긴 상태를 만든다(V55 이후 이 테이블의 인덱스는 PRIMARY 와 이것 둘뿐)</li>
 *   <li>순서 고정: SHOW INDEX -> DROP -> SHOW INDEX -> 워밍업+본측정 -> CREATE(DDL 시간 기록) -> SHOW INDEX -> 본측정</li>
 *   <li>규모마다 하위 조건 둘: PENDING 1,000행 / PENDING 0행(드레인 직후 - 매초 도는 틱의 지배적 상황)</li>
 *   <li>지표 둘: 원시 SQL(주) / {@code OutboxService.claimPending}(보조, 엔티티 매핑 포함)</li>
 *   <li>선점은 전부 롤백한다 - 커밋하면 다음 회가 같은 상태에서 돌지 않는다</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.outbox.*"}
 */
@Tag("manual")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxClaimLatencyLabTest extends OutboxLabSupport {

	private static final int LIMIT = 100;

	@AfterAll
	void restore() {
		closeLab();
	}

	@Test
	@DisplayName("step-01·02 — 규모 사다리마다 인덱스 유무로 선점 쿼리 지연과 실행 계획을 잰다")
	void 선점_쿼리_지연_곡선과_실행_계획을_규모별로_잰다() {
		List<OutboxLabScale> scales = OutboxLabConfig.scales();
		prepareLab(scales.get(scales.size() - 1).totalRows());

		OutboxLabRaw latencyBefore = new OutboxLabRaw("step-01", "before");
		OutboxLabRaw latencyAfter = new OutboxLabRaw("step-01", "after");
		OutboxLabRaw explainBefore = new OutboxLabRaw("step-02", "before");
		OutboxLabRaw explainAfter = new OutboxLabRaw("step-02", "after");

		Map<String, Object> base = baseCondition();
		base.put("claim_sql", CLAIM_SQL.formatted(LIMIT));
		base.put("scale_ladder", scales.toString());
		base.put("created_at_anchor", String.valueOf(seeder.anchor()));
		for (OutboxLabRaw raw : List.of(latencyBefore, latencyAfter, explainBefore, explainAfter)) {
			raw.baseCondition(base);
			raw.attach("scheduler-beans.txt", schedulerBeanReport());
		}
		assertThat(schedulersUnregistered())
				.as("auto-delivery=false 인데 스케줄러 빈이 떠 있으면 측정이 오염된다")
				.isTrue();

		for (OutboxLabScale scale : scales) {
			OutboxLabSeeder.SeedResult seed = seeder.ensureScale(scale);
			Map<String, Object> scaleCondition = scaleCondition(seed);

			// ── before: 인덱스 제거 ──────────────────────────────────────────────
			latencyBefore.attach("show-index-%s-1-before-drop.txt".formatted(scale), indexSwitch.showIndex());
			double dropMillis = indexSwitch.dropCurrent();
			jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
			String afterDrop = indexSwitch.showIndex();
			latencyBefore.attach("show-index-%s-2-after-drop.txt".formatted(scale), afterDrop);
			explainBefore.attach("show-index-%s.txt".formatted(scale), afterDrop);
			latencyBefore.note("%s: DROP INDEX %s 소요 %.1f ms".formatted(scale, OutboxLabIndexSwitch.CURRENT_INDEX,
					dropMillis));

			measureScale(latencyBefore, scale, scaleCondition, dropMillis);
			recordExplain(explainBefore, scale, scaleCondition);

			// ── after: 인덱스 복구 ───────────────────────────────────────────────
			double createMillis = indexSwitch.createCurrent();
			jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
			String afterCreate = indexSwitch.showIndex();
			latencyAfter.attach("show-index-%s-3-after-create.txt".formatted(scale), afterCreate);
			explainAfter.attach("show-index-%s.txt".formatted(scale), afterCreate);
			latencyAfter.note("%s: CREATE INDEX %s %s 소요 %.1f ms".formatted(scale,
					OutboxLabIndexSwitch.CURRENT_INDEX, OutboxLabIndexSwitch.CURRENT_COLUMNS, createMillis));

			measureScale(latencyAfter, scale, scaleCondition, createMillis);
			recordExplain(explainAfter, scale, scaleCondition);
		}

		for (OutboxLabRaw raw : List.of(latencyBefore, latencyAfter, explainBefore, explainAfter)) {
			raw.finish();
		}
		assertThat(latencyAfter.directory().resolve("run-summary.json")).exists();
		assertThat(indexSwitch.exists(OutboxLabIndexSwitch.CURRENT_INDEX))
				.as("측정이 끝나면 현행 인덱스가 되돌아와 있어야 한다")
				.isTrue();
	}

	/** 규모 한 점에서 PENDING 1,000 / 0 두 하위 조건 x 원시 SQL / 프로덕션 경로 두 지표. */
	private void measureScale(OutboxLabRaw raw, OutboxLabScale scale, Map<String, Object> scaleCondition,
			double ddlMillis) {
		Map<String, Object> pendingFull = withPending(scaleCondition, OutboxLabScale.PENDING_ROWS, ddlMillis);
		raw.series("%s-pending%d-raw-sql".formatted(scale, OutboxLabScale.PENDING_ROWS),
				"claim_query_latency_ms", "ms", repeat(() -> claimOnceMillis(CLAIM_SQL, LIMIT)), pendingFull);
		raw.series("%s-pending%d-service".formatted(scale, OutboxLabScale.PENDING_ROWS),
				"claim_via_service_latency_ms", "ms", repeat(() -> claimViaServiceMillis(LIMIT)), pendingFull);

		List<Long> pendingIds = seeder.pendingIds();
		seeder.markSent(pendingIds);
		Map<String, Object> pendingZero = withPending(scaleCondition, 0, ddlMillis);
		raw.series("%s-pending0-raw-sql".formatted(scale),
				"claim_query_latency_ms", "ms", repeat(() -> claimOnceMillis(CLAIM_SQL, LIMIT)), pendingZero);
		raw.series("%s-pending0-service".formatted(scale),
				"claim_via_service_latency_ms", "ms", repeat(() -> claimViaServiceMillis(LIMIT)), pendingZero);
		seeder.resetPendingRows(pendingIds);
	}

	private void recordExplain(OutboxLabRaw raw, OutboxLabScale scale, Map<String, Object> scaleCondition) {
		ExplainOutcome outcome = explain(CLAIM_SQL, LIMIT);
		String fileName = "explain-%s.txt".formatted(scale);
		raw.attach(fileName, """
				-- 규모: %s (총 %s행, PENDING %d행)
				-- 방식: %s
				-- 쿼리:
				%s

				%s
				""".formatted(scale, scaleCondition.get("rows"), OutboxLabScale.PENDING_ROWS, outcome.mode(),
				CLAIM_SQL.formatted(LIMIT), outcome.text()));

		Map<String, Object> observation = new LinkedHashMap<>(scaleCondition);
		observation.put("explain_mode", outcome.mode());
		observation.put("explain_rejection", outcome.rejection());
		observation.put("sort_node_present", containsSort(outcome.text()));
		observation.put("index_used_in_plan", outcome.text().contains(OutboxLabIndexSwitch.CURRENT_INDEX));
		observation.put("explain_file", fileName);
		raw.observation("explain-%s".formatted(scale), observation);
	}

	/** 실행 계획에 정렬 노드가 있는가 - MySQL 은 "Sort:" 또는 "filesort" 로 드러낸다. */
	static boolean containsSort(String explainText) {
		String lower = explainText.toLowerCase();
		return lower.contains("-> sort") || lower.contains("filesort") || lower.contains("\"sort\"");
	}

	private static Map<String, Object> withPending(Map<String, Object> scaleCondition, int pendingRows,
			double ddlMillis) {
		Map<String, Object> condition = new LinkedHashMap<>(scaleCondition);
		condition.put("pending_rows", pendingRows);
		condition.put("limit", LIMIT);
		condition.put("index_ddl_ms", OutboxLabStats.round(ddlMillis));
		return condition;
	}
}
