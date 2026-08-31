package modi.backend.ingestionv2.lab.retry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;
import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestionv2.collect.domain.CultureCatalogClient;
import modi.backend.ingestionv2.common.IngestionDeliveryFacade;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.deadletter.DeadLetterService;
import modi.backend.ingestionv2.common.outbox.OutboxDispatcher;
import modi.backend.ingestionv2.common.outbox.OutboxService;
import modi.backend.ingestionv2.enrich.domain.detail.CultureDetailClient;
import modi.backend.ingestionv2.enrich.domain.genre.GenreClassifier;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceHoursClient;

/**
 * 재시도·DLQ 실험 하네스의 공통 토대 - 재시도 정책·격리 체계 검증 측정 전용.
 *
 * <p><b>자동 스위트에서 빠져 있다.</b> {@code @Tag("manual")} 이 붙어 {@code ./gradlew test} 는 이 클래스를
 * 건너뛴다. 여기서 manual 이 뜻하는 것은 외부 API 호출이 아니라 <b>수천~수만 요청의 동시 부하</b>다. 돌리는
 * 명령에는 반드시 필터를 붙인다:
 *
 * <pre>./gradlew manualTest --tests "modi.backend.ingestionv2.lab.retry.*"</pre>
 *
 * <p>필터 없이 {@code manualTest} 를 돌리면 {@code GeminiClientManualTest}·{@code OpenAiClientManualTest} 가
 * 함께 실행돼 <b>실제 유료 API</b>를 호출한다. 금지.
 *
 * <ul>
 *   <li>{@code auto-delivery=false} 를 빠뜨리면 발송·회수 스케줄러가 측정 중에 같은 행과 같은 스트림을
 *       건드려 결과가 통째로 오염된다</li>
 *   <li>{@code IngestionTestSupport} 를 상속하지 않는다 - 그쪽 {@code @BeforeEach} 가 매 테스트 테이블을 비운다</li>
 *   <li>커넥션 풀을 스레드 수보다 크게 잡는다 - 기본 10 이면 50스레드 run 의 대기가 <b>행 잠금이 아니라
 *       풀 고갈</b>이 되어 지표 3·5·8 이 전부 무의미해진다</li>
 *   <li>밖으로 나가는 포트 넷은 스텁 - 측정 중 외부 호출이 끼어들 여지를 없앤다</li>
 *   <li>시딩·측정·정리를 클래스 안에서 닫는다(공유 컨테이너 오염 방지)</li>
 * </ul>
 */
@Tag("manual")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.ingestion.v2.enabled=true",
		"app.ingestion.v2.auto-delivery=false",
		"app.ingestion.v2.claim-strategy=SKIP_LOCKED",
		"app.exhibition.enrich.scheduling-enabled=false",
		"spring.datasource.hikari.maximum-pool-size=80",
		"spring.datasource.hikari.connection-timeout=60000"
})
abstract class RetryLabSupport {

	/** 측정 대상이 아닌 슬라이스 테이블 - 매 run 시작 전에 비운다. */
	protected static final List<String> SLICE_TABLES = List.of(
			"ingestion_outbox", "ingestion_dead_letter",
			"ingestion_staging", "ingestion_inspection",
			"ingestion_enrichment", "ingestion_enrichment_detail",
			"ingestion_enrichment_genre", "ingestion_enrichment_hours",
			"ingestion_collection", "ingestion_collect_batch_mark",
			"ingestion_culture_list_snapshot", "ingestion_culture_detail_snapshot",
			"ingestion_genre_snapshot", "ingestion_google_place_snapshot");

	@MockitoBean protected CultureCatalogClient catalogClient;
	@MockitoBean protected CultureDetailClient detailClient;
	@MockitoBean protected GenreClassifier genreClassifier;
	@MockitoBean protected PlaceHoursClient placeHoursClient;

	@Autowired protected IngestionDeliveryFacade ingestionDeliveryFacade;
	@Autowired protected DeadLetterService deadLetterService;
	@Autowired protected OutboxService outboxService;
	@Autowired protected OutboxDispatcher outboxDispatcher;
	@Autowired protected IngestionProperties properties;
	@Autowired protected JdbcTemplate jdbcTemplate;
	@Autowired protected StringRedisTemplate redisTemplate;
	@Autowired protected TransactionTemplate transactionTemplate;
	@Autowired protected EntityManagerFactory entityManagerFactory;

	protected void clearSliceTables() {
		SLICE_TABLES.forEach(table -> jdbcTemplate.execute("delete from " + table));
	}

	/** 원시 파일 조건의 공통 부분 - 프로토콜 §5 필수 필드. */
	protected Map<String, Object> commonCondition() {
		Map<String, Object> condition = new LinkedHashMap<>();
		condition.put("commit", gitCommit());
		condition.put("mysql", mysqlVersion());
		condition.put("redis", redisVersion());
		condition.put("instances", "L1:single-context");
		condition.put("measured_at", java.time.LocalDateTime.now().toString());
		condition.put("lab_config", RetryLabConfig.describe());
		condition.put("hikari_max_pool_size", 80);
		return condition;
	}

	/** 조건에 적는 값은 <b>실측</b>이다 - 문서에 적힌 버전과 컨테이너가 띄운 버전이 다를 수 있다. */
	protected String mysqlVersion() {
		try {
			return "testcontainers mysql " + jdbcTemplate.queryForObject("select version()", String.class);
		} catch (RuntimeException unavailable) {
			return "(unknown: " + unavailable.getMessage() + ")";
		}
	}

	/** 같은 이유로 Redis 버전도 서버에게 직접 묻는다. */
	protected String redisVersion() {
		try {
			Properties info = redisTemplate.execute(
					(RedisCallback<Properties>) connection -> connection.serverCommands().info("server"));
			String version = info == null ? null : info.getProperty("redis_version");
			return "testcontainers redis " + (version == null ? "(unknown)" : version);
		} catch (RuntimeException unavailable) {
			return "(unknown: " + unavailable.getMessage() + ")";
		}
	}

	/** before 진위 확인의 한 줄 - 어느 커밋에서 잰 값인지. */
	protected static String gitCommit() {
		try {
			Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
			try (var reader = process.inputReader()) {
				String line = reader.readLine();
				return line == null ? "(unknown)" : line.trim();
			}
		} catch (Exception unavailable) {
			return "(unknown: " + unavailable.getMessage() + ")";
		}
	}

	/** {@code @Version} 컬럼의 유무 - Phase A/B 를 가르는 before 진위 확인. */
	protected String versionColumnCheck() {
		List<Map<String, Object>> columns = jdbcTemplate.queryForList(
				"show columns from ingestion_dead_letter like 'version'");
		return columns.isEmpty() ? "(없음)" : columns.toString();
	}
}
