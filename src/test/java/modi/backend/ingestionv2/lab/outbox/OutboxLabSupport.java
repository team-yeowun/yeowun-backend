package modi.backend.ingestionv2.lab.outbox;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestionv2.collect.domain.CultureCatalogClient;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.interfaces.OutboxDispatchScheduler;
import modi.backend.ingestionv2.common.interfaces.PendingReclaimScheduler;
import modi.backend.ingestionv2.common.interfaces.StreamTrimScheduler;
import modi.backend.ingestionv2.common.outbox.OutboxDispatcher;
import modi.backend.ingestionv2.common.outbox.OutboxService;
import modi.backend.ingestionv2.enrich.domain.detail.CultureDetailClient;
import modi.backend.ingestionv2.enrich.domain.genre.GenreClassifier;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceHoursClient;

/**
 * 아웃박스 부하 실험 하네스의 공통 토대 - 인덱스 설계 검증 측정 전용.
 *
 * <p><b>자동 스위트에서 빠져 있다.</b> {@code @Tag("manual")} 이 붙어 {@code ./gradlew test} 는 이 클래스를
 * 건너뛴다. 기존 manual 태그는 "실제 외부 API 를 호출하는 수동 확인용"이라는 뜻이었는데, 여기서는
 * <b>외부 API 호출이 아니라 대용량 부하</b>(최대 500만 행 적재)라서 자동 실행에서 빼는 것이다 - 태그의 의미가
 * 그만큼 넓어졌다. 돌리는 명령은 반드시 필터를 붙인다:
 *
 * <pre>./gradlew manualTest --tests "modi.backend.ingestionv2.lab.outbox.*"</pre>
 *
 * <p>필터 없이 {@code manualTest} 를 돌리면 {@code GeminiClientManualTest}·{@code OpenAiClientManualTest} 가
 * 함께 실행돼 <b>실제 유료 API</b>를 호출한다. 금지.
 *
 * <ul>
 *   <li>프로퍼티 넷은 계획서 F-06 값 그대로 - auto-delivery=false 를 빠뜨리면 발송 스케줄러가 1초마다
 *       인덱스 없는 100만 행을 풀스캔하며 FOR UPDATE 잠금을 걸어 측정이 통째로 오염된다</li>
 *   <li>{@code IngestionTestSupport} 를 상속하지 않는다 - 그쪽 @BeforeEach 가 매 테스트 테이블을 비운다</li>
 *   <li>밖으로 나가는 포트 넷은 스텁 - 측정 중 외부 호출이 끼어들 여지를 없앤다</li>
 *   <li>시딩·측정·정리를 클래스 안에서 닫는다(공유 컨테이너 오염 방지)</li>
 * </ul>
 */
@Tag("manual")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.ingestion.v2.enabled=true",
		"app.ingestion.v2.auto-delivery=false",
		"app.exhibition.enrich.scheduling-enabled=false",
		"app.local-seed.enabled=false"
})
abstract class OutboxLabSupport {

	/** {@code OutboxJpaRepository.claimPending} 과 같은 텍스트. :limit 만 값으로 바뀐다. */
	static final String CLAIM_SQL = """
			SELECT * FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY created_at, id
			LIMIT %d
			FOR UPDATE SKIP LOCKED""";

	/** V54:30 주석이 가정한 순서(적재 순서 = id 순서). step-05 3차의 반증 대상. */
	static final String CLAIM_BY_ID_SQL = """
			SELECT * FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY id
			LIMIT %d
			FOR UPDATE SKIP LOCKED""";

	@MockitoBean protected CultureCatalogClient catalogClient;
	@MockitoBean protected CultureDetailClient detailClient;
	@MockitoBean protected GenreClassifier genreClassifier;
	@MockitoBean protected PlaceHoursClient placeHoursClient;

	@Autowired protected ApplicationContext context;
	@Autowired protected JdbcTemplate jdbcTemplate;
	@Autowired protected DataSource dataSource;
	@Autowired protected TransactionTemplate transactionTemplate;
	@Autowired protected OutboxService outboxService;
	@Autowired protected OutboxDispatcher outboxDispatcher;
	@Autowired protected IngestionProperties properties;

	/** 시딩 전용 커넥션을 열려고 컨테이너에서 접속 정보를 얻는다. 측정 경로는 여전히 애플리케이션 DataSource 다. */
	@Autowired(required = false) protected MySQLContainer mysqlContainer;

	protected OutboxLabSeeder seeder;
	protected OutboxLabIndexSwitch indexSwitch;

	protected void prepareLab(long maxScaleRows) {
		this.indexSwitch = new OutboxLabIndexSwitch(jdbcTemplate);
		this.seeder = new OutboxLabSeeder(dataSource, jdbcTemplate, maxScaleRows, IngestionClock.now(),
				seedConnection());
	}

	/**
	 * 시딩 전용 접속 정보. 컨테이너 빈이 있으면 거기서 얻어 {@code rewriteBatchedStatements=true} 를 붙인다.
	 * 없으면 null - 시더가 애플리케이션 DataSource 로 물러난다.
	 */
	private String[] seedConnection() {
		if (mysqlContainer == null) {
			return null;
		}
		return new String[] {mysqlContainer.getJdbcUrl(), mysqlContainer.getUsername(),
				mysqlContainer.getPassword()};
	}

	/** 클래스가 끝날 때 반드시 부른다 - 인덱스 원상 복구 + 테이블 비우기. */
	protected void closeLab() {
		if (indexSwitch != null) {
			indexSwitch.restoreProduction();
		}
		if (seeder != null && OutboxLabConfig.truncateAfterClass()) {
			seeder.truncate();
		}
	}

	// ── before_check ────────────────────────────────────────────────────────────

	/**
	 * 계획서 F-06 이 의무화한 출력 - 세 스케줄러 빈이 등록되지 않았음을 확인한다.
	 * 셋 다 {@code @ConditionalOnProperty(... {"enabled","auto-delivery"}, havingValue="true")} 라
	 * auto-delivery=false 면 미등록이어야 한다. 이 출력이 원시 파일에 없으면 그 run 은 무효다.
	 */
	protected String schedulerBeanReport() {
		StringBuilder text = new StringBuilder("스케줄러 빈 등록 확인 (auto-delivery=false 이므로 셋 다 0개여야 한다)\n\n");
		for (Class<?> type : List.of(OutboxDispatchScheduler.class, PendingReclaimScheduler.class,
				StreamTrimScheduler.class)) {
			String[] names = context.getBeanNamesForType(type);
			text.append("context.getBeanNamesForType(").append(type.getSimpleName()).append(".class) -> ")
					.append(names.length).append("개 ").append(Arrays.toString(names)).append('\n');
		}
		text.append("\napp.ingestion.v2.enabled=")
				.append(context.getEnvironment().getProperty("app.ingestion.v2.enabled"))
				.append("\napp.ingestion.v2.auto-delivery=")
				.append(context.getEnvironment().getProperty("app.ingestion.v2.auto-delivery"))
				.append("\napp.ingestion.v2.dispatch-interval-ms=")
				.append(context.getEnvironment().getProperty("app.ingestion.v2.dispatch-interval-ms"))
				.append("\napp.ingestion.v2.dispatch-batch-size=")
				.append(context.getEnvironment().getProperty("app.ingestion.v2.dispatch-batch-size"))
				.append("\napp.ingestion.v2.cleanup-batch-size=")
				.append(context.getEnvironment().getProperty("app.ingestion.v2.cleanup-batch-size"))
				.append("\napp.ingestion.v2.retention-days=")
				.append(context.getEnvironment().getProperty("app.ingestion.v2.retention-days"))
				.append('\n');
		return text.toString();
	}

	protected boolean schedulersUnregistered() {
		return context.getBeanNamesForType(OutboxDispatchScheduler.class).length == 0
				&& context.getBeanNamesForType(PendingReclaimScheduler.class).length == 0
				&& context.getBeanNamesForType(StreamTrimScheduler.class).length == 0;
	}

	// ── 조건 기록 ───────────────────────────────────────────────────────────────

	/** 전 스텝 공통 조건. 규모별 값(rows·avg_row_length 등)은 계열마다 덧붙인다. */
	protected Map<String, Object> baseCondition() {
		Map<String, Object> condition = new LinkedHashMap<>();
		condition.put("mysql", jdbcTemplate.queryForObject("SELECT VERSION()", String.class) + " (testcontainers)");
		condition.put("buffer_pool", jdbcTemplate.queryForObject("SELECT @@innodb_buffer_pool_size", Long.class));
		condition.put("innodb_flush_log_at_trx_commit",
				jdbcTemplate.queryForObject("SELECT @@innodb_flush_log_at_trx_commit", Integer.class));
		condition.put("batch", properties.dispatchBatchSize());
		condition.put("cleanup_batch_size", properties.cleanupBatchSize());
		condition.put("retention_days", properties.retentionDays());
		condition.put("instances", "해당 없음(단일 스레드)");
		condition.put("commit", gitCommit());
		condition.put("working_tree", workingTree());
		condition.put("measured_at", ZonedDateTime.now(IngestionClock.ZONE).toString());
		condition.put("jvm", System.getProperty("java.version") + " / " + System.getProperty("os.name") + " "
				+ System.getProperty("os.arch"));
		condition.put("lab_config", OutboxLabConfig.describe());
		condition.put("seed_method", "JDBC batch insert, %d건/배치, autocommit off, 배치마다 commit / %s"
				.formatted(OutboxLabSeeder.BATCH_SIZE, seeder == null ? "(미준비)" : seeder.seedConnectionDescription()));
		condition.put("rows_per_day", OutboxLabScale.ROWS_PER_DAY);
		condition.put("schedulers_unregistered", schedulersUnregistered());
		return condition;
	}

	/** 규모 점 하나의 조건 - 시딩 결과 + 테이블 통계. */
	protected Map<String, Object> scaleCondition(OutboxLabSeeder.SeedResult seed) {
		Map<String, Object> condition = new LinkedHashMap<>(seed.toMap());
		condition.putAll(tableStats());
		condition.put("status_counts", seeder.countByStatus());
		return condition;
	}

	/**
	 * information_schema 통계. 캐시 만료를 0으로 두고 같은 커넥션에서 읽어야 방금 적재분이 반영된다.
	 * (F-10 - payload 크기 추정 대신 실측으로 버퍼 풀 대조를 한다)
	 */
	protected Map<String, Object> tableStats() {
		return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Map<String, Object>>) connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("SET SESSION information_schema_stats_expiry = 0");
				try (var rows = statement.executeQuery("""
						SELECT table_rows, avg_row_length, data_length, index_length
						FROM information_schema.TABLES
						WHERE table_schema = DATABASE() AND table_name = 'ingestion_outbox'
						""")) {
					Map<String, Object> stats = new LinkedHashMap<>();
					if (rows.next()) {
						stats.put("information_schema_table_rows", rows.getLong("table_rows"));
						stats.put("avg_row_length", rows.getLong("avg_row_length"));
						stats.put("data_length", rows.getLong("data_length"));
						stats.put("index_length", rows.getLong("index_length"));
					}
					return stats;
				}
			}
		});
	}

	// ── 측정 도구 ───────────────────────────────────────────────────────────────

	/**
	 * 워밍업 뒤 본측정 N회. 매 회 {@code action} 이 돌려주는 ms 를 모은다.
	 * 워밍업 값은 집계에서 빠지되 첫 회 비용이 궁금할 수 있어 로그로만 남긴다.
	 */
	protected double[] repeat(Supplier<Double> action) {
		for (int index = 0; index < OutboxLabConfig.warmup(); index++) {
			action.get();
		}
		List<Double> values = new ArrayList<>();
		for (int index = 0; index < OutboxLabConfig.runs(); index++) {
			values.add(action.get());
		}
		double[] result = new double[values.size()];
		for (int index = 0; index < values.size(); index++) {
			result[index] = values.get(index);
		}
		return result;
	}

	/** 선점 쿼리 한 번 - 반드시 롤백한다(선점 결과를 커밋하면 다음 회가 같은 상태에서 돌지 않는다). */
	protected double claimOnceMillis(String sql, int limit) {
		return transactionTemplate.execute(status -> {
			long startedAt = System.nanoTime();
			jdbcTemplate.queryForList(sql.formatted(limit));
			double elapsed = (System.nanoTime() - startedAt) / 1_000_000d;
			status.setRollbackOnly();
			return elapsed;
		});
	}

	/** 프로덕션 경로(OutboxService.claimPending) 한 번 - 엔티티 매핑 비용이 포함된다. */
	protected double claimViaServiceMillis(int limit) {
		return transactionTemplate.execute(status -> {
			long startedAt = System.nanoTime();
			outboxService.claimPending(limit);
			double elapsed = (System.nanoTime() - startedAt) / 1_000_000d;
			status.setRollbackOnly();
			return elapsed;
		});
	}

	/** 선점 쿼리가 실제로 집어 오는 id 목록. 롤백한다. */
	protected List<Long> claimedIds(String sql, int limit) {
		return transactionTemplate.execute(status -> {
			List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.formatted(limit));
			status.setRollbackOnly();
			return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
		});
	}

	// ── EXPLAIN ────────────────────────────────────────────────────────────────

	/** 실행 계획 산출 결과 - 어떤 폴백을 썼는지까지 남긴다(계획서 R1). */
	record ExplainOutcome(String mode, String text, String rejection) {
	}

	/**
	 * EXPLAIN ANALYZE 를 잠금 절과 함께 시도하고, 거부되면 두 단계로 물러난다.
	 *
	 * <ol>
	 *   <li>{@code EXPLAIN ANALYZE <선점 쿼리(FOR UPDATE SKIP LOCKED)>}</li>
	 *   <li>거부되면 잠금 절 없는 같은 SELECT 로 {@code EXPLAIN ANALYZE}(잠금 비용 미포함)</li>
	 *   <li>추가로 잠금 절 포함 {@code EXPLAIN FORMAT=JSON}</li>
	 * </ol>
	 */
	protected ExplainOutcome explain(String sql, int limit) {
		String statement = sql.formatted(limit);
		try {
			return new ExplainOutcome("EXPLAIN ANALYZE (잠금 절 포함)", runExplain("EXPLAIN ANALYZE " + statement), null);
		} catch (RuntimeException rejected) {
			String rejection = String.valueOf(rejected.getMessage());
			String withoutLock = statement.replace("\nFOR UPDATE SKIP LOCKED", "");
			StringBuilder text = new StringBuilder();
			text.append("-- 1단계 거부 원문 --\n").append(rejection).append("\n\n");
			try {
				text.append("-- 2단계: 잠금 절 없는 EXPLAIN ANALYZE (잠금 비용 미포함) --\n")
						.append(runExplain("EXPLAIN ANALYZE " + withoutLock)).append('\n');
			} catch (RuntimeException alsoRejected) {
				text.append("(2단계도 거부됨) ").append(alsoRejected.getMessage()).append('\n');
			}
			try {
				text.append("\n-- 3단계: 잠금 절 포함 EXPLAIN FORMAT=JSON --\n")
						.append(runExplain("EXPLAIN FORMAT=JSON " + statement)).append('\n');
			} catch (RuntimeException alsoRejected) {
				text.append("(3단계도 거부됨) ").append(alsoRejected.getMessage()).append('\n');
			}
			return new ExplainOutcome("폴백(2단계 잠금 없는 ANALYZE + 3단계 FORMAT=JSON)", text.toString(), rejection);
		}
	}

	private String runExplain(String statement) {
		return transactionTemplate.execute(status -> {
			List<Map<String, Object>> rows = jdbcTemplate.queryForList(statement);
			status.setRollbackOnly();
			StringBuilder text = new StringBuilder();
			for (Map<String, Object> row : rows) {
				row.values().forEach(value -> text.append(value).append('\n'));
			}
			return text.toString();
		});
	}

	// ── 잡동사니 ────────────────────────────────────────────────────────────────

	protected LocalDateTime retentionThreshold() {
		return IngestionClock.now().minusDays(properties.retentionDays());
	}

	private static String gitCommit() {
		return runCommand("git", "rev-parse", "--short", "HEAD");
	}

	private static String workingTree() {
		String changed = runCommand("git", "status", "--porcelain");
		long files = changed.isBlank() ? 0 : changed.lines().count();
		return files == 0 ? "clean" : "dirty(" + files + " files)";
	}

	private static String runCommand(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes()).trim();
			process.waitFor();
			return output;
		} catch (Exception unavailable) {
			Thread.currentThread().interrupt();
			return "(알 수 없음: " + unavailable.getMessage() + ")";
		}
	}
}
