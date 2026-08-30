package modi.backend.ingestionv2.lab.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * step-05(정렬 키 선택 논증) 하네스 - <b>실험 범위 결정으로 미사용</b>.
 *
 * <p>2026-08-29 실험 범위 결정으로 아웃박스 인덱스 실험의 측정 범위가 <b>before(인덱스 없음) / after((status, created_at))
 * 두 조건</b>으로 좁혀졌다. 이 클래스는 그 결정 전에 이미 만들어져 한 번 초록으로 돈 상태라 지우지 않고
 * {@code @Disabled} 로 재워 둔다 - 와일드카드 실행({@code --tests "...lab.outbox.*"})이 이 클래스를 건너뛰므로
 * 측정 시간이 늘지 않는다. 범위가 다시 넓어지면 이 어노테이션만 떼면 된다.
 *
 * <p>아래 설명은 그때를 위한 기록이다.
 *
 * <ul>
 *   <li>후보 넷을 하나씩 만들고 지운다: ①없음 ②(status) ③(status, id) ④(status, created_at)</li>
 *   <li><b>1차 판정은 실행 계획</b> - ③에 Sort(filesort) 노드가 나타나고 ④에는 없을 것</li>
 *   <li>2차는 선점 쿼리 p95. 차이가 잡음에 묻혀도 실패가 아니다(PENDING 1,000행의 filesort 는 작다)</li>
 *   <li><b>3차 반증 케이스</b> - created_at 순서와 id 순서가 어긋난 데이터. 어긋남의 실제 발생원은
 *       두 시각의 출처가 다르다는 데 있다: created_at 은 애플리케이션 JVM 이 {@code IngestionClock.now()} 로 찍고,
 *       id 는 DB 가 INSERT 시점에 auto_increment 로 붙인다. 긴 트랜잭션이나 인스턴스 간 시계차에서 갈린다.
 *       (관리자 재시도 {@code Outbox.retry()} 는 같은 행의 status·retryCount 만 바꾸고 새 id 를 주지 않으므로
 *       발생원이 아니다 - 근거로 쓰지 말 것)</li>
 *   <li>기각할 가정의 원문은 {@code V54__ingestion_v2_redesign.sql:30} 주석 "적재 순서 = id 순서"</li>
 *   <li>원시 파일 배치: {@code before} = 기각 후보 ①②③, {@code after} = 현행 후보 ④</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew manualTest --tests "modi.backend.ingestionv2.lab.outbox.*"}
 */
@Tag("manual")
@Disabled("실험 범위 결정(2026-08-29)으로 제외 — before(인덱스 없음)/after((status, created_at)) 두 조건만 측정한다")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxIndexCandidateLabTest extends OutboxLabSupport {

	private static final int LIMIT = 100;
	private static final String V54_ASSUMPTION =
			"V54__ingestion_v2_redesign.sql:30 -- 정리 배치의 status = 'CONFIRMED' AND created_at < ? ORDER BY id 도 "
					+ "이 인덱스를 탄다(적재 순서 = id 순서).";

	@AfterAll
	void restore() {
		closeLab();
	}

	@Test
	@DisplayName("step-05 — 정렬 키 후보 넷의 실행 계획·지연과, 순서가 어긋난 데이터에서의 발행 순서를 본다")
	void 정렬_키_후보를_실행_계획과_반증_케이스로_가른다() {
		List<OutboxLabScale> scales = OutboxLabConfig.scales();
		OutboxLabScale scale = scales.get(scales.size() - 1);
		prepareLab(scale.totalRows());

		OutboxLabRaw rejected = new OutboxLabRaw("step-05", "before");
		OutboxLabRaw current = new OutboxLabRaw("step-05", "after");
		Map<String, Object> base = baseCondition();
		base.put("claim_sql", CLAIM_SQL.formatted(LIMIT));
		base.put("claim_by_id_sql", CLAIM_BY_ID_SQL.formatted(LIMIT));
		base.put("rejected_assumption_source", V54_ASSUMPTION);
		rejected.baseCondition(base);
		current.baseCondition(base);
		rejected.attach("scheduler-beans.txt", schedulerBeanReport());
		current.attach("scheduler-beans.txt", schedulerBeanReport());
		assertThat(schedulersUnregistered()).isTrue();

		OutboxLabSeeder.SeedResult seed = seeder.ensureScale(scale);
		Map<String, Object> scaleCondition = scaleCondition(seed);
		scaleCondition.put("pending_rows", OutboxLabScale.PENDING_ROWS);
		scaleCondition.put("limit", LIMIT);

		// ── 1차·2차: 정상 데이터에서 후보 넷 ──────────────────────────────────────
		for (OutboxLabIndexSwitch.Candidate candidate : OutboxLabIndexSwitch.Candidate.values()) {
			OutboxLabRaw raw = target(candidate, rejected, current);
			double ddlMillis = indexSwitch.applyOnly(candidate);
			jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
			String key = candidate.name().toLowerCase();
			raw.attach("show-index-normal-%s.txt".formatted(key), indexSwitch.showIndex());

			Map<String, Object> condition = new LinkedHashMap<>(scaleCondition);
			condition.put("candidate", candidate.label());
			condition.put("candidate_index", candidate.indexName() + " " + candidate.columns());
			condition.put("index_ddl_ms", OutboxLabStats.round(ddlMillis));
			condition.put("data_case", "정상(created_at 순서 = id 순서)");

			ExplainOutcome outcome = explain(CLAIM_SQL, LIMIT);
			raw.attach("explain-normal-%s.txt".formatted(key), explainFile(candidate, "정상", outcome));
			Map<String, Object> planObservation = new LinkedHashMap<>(condition);
			planObservation.put("explain_mode", outcome.mode());
			planObservation.put("explain_rejection", outcome.rejection());
			planObservation.put("sort_node_present", OutboxClaimLatencyLabTest.containsSort(outcome.text()));
			planObservation.put("explain_file", "explain-normal-%s.txt".formatted(key));
			raw.observation("plan-normal-%s".formatted(key), planObservation);

			raw.series("normal-%s".formatted(key), "claim_query_latency_ms", "ms",
					repeat(() -> claimOnceMillis(CLAIM_SQL, LIMIT)), condition);
		}

		// ── 3차: created_at 순서와 id 순서가 어긋난 데이터 ────────────────────────
		List<Object[]> originalCreatedAt = seeder.skewHalfOfPending();
		jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
		Map<String, Object> skewNote = new LinkedHashMap<>(scaleCondition);
		skewNote.put("data_case", "반증(PENDING 1,000행 중 절반의 created_at 을 id 순서와 역방향으로 부여)");
		skewNote.put("skew_origin", "created_at 은 애플리케이션 JVM(IngestionClock.now()), id 는 DB auto_increment - "
				+ "출처가 달라 긴 트랜잭션·인스턴스 간 시계차에서 두 순서가 갈린다");
		skewNote.put("not_the_origin", "Outbox.retry() 는 같은 행의 status·retryCount 만 바꾸고 새 id 를 주지 않으므로 발생원이 아니다");

		Map<String, List<Long>> claimedByCandidate = new LinkedHashMap<>();
		for (OutboxLabIndexSwitch.Candidate candidate : List.of(OutboxLabIndexSwitch.Candidate.STATUS_ID,
				OutboxLabIndexSwitch.Candidate.STATUS_CREATED)) {
			OutboxLabRaw raw = target(candidate, rejected, current);
			double ddlMillis = indexSwitch.applyOnly(candidate);
			jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");
			String key = candidate.name().toLowerCase();
			raw.attach("show-index-skewed-%s.txt".formatted(key), indexSwitch.showIndex());

			Map<String, Object> condition = new LinkedHashMap<>(skewNote);
			condition.put("candidate", candidate.label());
			condition.put("candidate_index", candidate.indexName() + " " + candidate.columns());
			condition.put("index_ddl_ms", OutboxLabStats.round(ddlMillis));

			ExplainOutcome outcome = explain(CLAIM_SQL, LIMIT);
			raw.attach("explain-skewed-%s.txt".formatted(key), explainFile(candidate, "반증", outcome));
			Map<String, Object> planObservation = new LinkedHashMap<>(condition);
			planObservation.put("explain_mode", outcome.mode());
			planObservation.put("sort_node_present", OutboxClaimLatencyLabTest.containsSort(outcome.text()));
			planObservation.put("explain_file", "explain-skewed-%s.txt".formatted(key));
			raw.observation("plan-skewed-%s".formatted(key), planObservation);

			raw.series("skewed-%s".formatted(key), "claim_query_latency_ms", "ms",
					repeat(() -> claimOnceMillis(CLAIM_SQL, LIMIT)), condition);

			claimedByCandidate.put(key, claimedIds(CLAIM_SQL, LIMIT));
		}

		// (b) 발행 순서 어긋남 - "적재 순서 = id 순서" 가정이 집어 오는 100행과 요구 순서의 100행 비교
		List<Long> byCreatedAt = claimedIds(CLAIM_SQL, LIMIT);
		List<Long> byId = claimedIds(CLAIM_BY_ID_SQL, LIMIT);
		Map<String, Object> ordering = new LinkedHashMap<>(skewNote);
		ordering.put("order_by_created_at_ids_first10", byCreatedAt.stream().limit(10).toList());
		ordering.put("order_by_id_ids_first10", byId.stream().limit(10).toList());
		ordering.put("overlap_count", overlap(byCreatedAt, byId));
		ordering.put("only_in_order_by_created_at", difference(byCreatedAt, byId).size());
		ordering.put("only_in_order_by_id", difference(byId, byCreatedAt).size());
		ordering.put("same_set", overlap(byCreatedAt, byId) == LIMIT);
		ordering.put("same_sequence", byCreatedAt.equals(byId));
		ordering.put("meaning", "(status, id) 인덱스가 공짜로 주는 순서는 ORDER BY id 다. 두 집합이 다르면 "
				+ "LIMIT 100 이 '가장 오래된 100건'이 아니게 된다 - 이것이 " + V54_ASSUMPTION + " 가정의 기각 사유다.");
		ordering.put("claimed_ids_same_across_candidates",
				claimedByCandidate.size() == 2
						&& claimedByCandidate.get("status_id").equals(claimedByCandidate.get("status_created")));
		ordering.put("claimed_ids_note", "현행 쿼리 텍스트(ORDER BY created_at, id)를 쓰는 한 결과 집합은 인덱스와 무관하게 같다. "
				+ "인덱스가 가르는 것은 정렬 비용이고, 순서가 갈리는 것은 ORDER BY 를 id 로 바꿨을 때다.");
		rejected.observation("publication-order-skew", ordering);

		seeder.restoreCreatedAt(originalCreatedAt);
		indexSwitch.restoreProduction();
		jdbcTemplate.execute("ANALYZE TABLE ingestion_outbox");

		rejected.finish();
		current.finish();
		assertThat(current.directory().resolve("run-summary.json")).exists();
		assertThat(indexSwitch.exists(OutboxLabIndexSwitch.CURRENT_INDEX)).isTrue();
	}

	private static OutboxLabRaw target(OutboxLabIndexSwitch.Candidate candidate, OutboxLabRaw rejected,
			OutboxLabRaw current) {
		return candidate == OutboxLabIndexSwitch.Candidate.STATUS_CREATED ? current : rejected;
	}

	private String explainFile(OutboxLabIndexSwitch.Candidate candidate, String dataCase, ExplainOutcome outcome) {
		return """
				-- 후보: %s (%s %s)
				-- 데이터: %s
				-- 방식: %s
				-- 쿼리:
				%s

				%s
				""".formatted(candidate.label(), candidate.indexName(), candidate.columns(), dataCase,
				outcome.mode(), CLAIM_SQL.formatted(LIMIT), outcome.text());
	}

	private static int overlap(List<Long> left, List<Long> right) {
		return (int) left.stream().filter(right::contains).count();
	}

	private static List<Long> difference(List<Long> left, List<Long> right) {
		List<Long> only = new ArrayList<>(left);
		only.removeAll(right);
		return only;
	}
}
