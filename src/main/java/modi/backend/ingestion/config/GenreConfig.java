package modi.backend.ingestion.config;

import java.net.http.HttpClient;
import java.time.Duration;

import modi.backend.ingestion.properties.CatalogEnrichProperties;
import modi.backend.ingestion.properties.GeminiProperties;
import modi.backend.ingestion.properties.GenreClaudeProperties;
import modi.backend.ingestion.properties.GenreProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.ingestion.infra.claude.ClaudeGenreClassifier;
import modi.backend.ingestion.infra.failover.FailoverGenreClassifier;
import modi.backend.ingestion.infra.gemini.GeminiGenreClassifier;
import modi.backend.ingestion.infra.mock.MockGenreClassifier;

/**
 * 장르 분류 관련 빈 등록. Gemini 전용 RestClient와, yml로 선택되는 주 분류기({@link GenreClassifier})를 조립한다.
 * <p>
 * 구현들은 {@code @Component}로 항상 빈으로 <b>공존</b>하고, 여기서 {@code app.exhibition.genre.classifier}에 따라
 * 주 분류기(@Primary)를 고른다 — 주입 지점(처리기)은 선택된 하나만 본다.
 * {@code gemini}면 <b>폴백 체인</b>(1차 Gemini → 2차 Claude, resilience4j Retry+CircuitBreaker — ADR-11),
 * 그 외(기본 {@code mock})면 결정적 mock이다. 설정만 바꿔 무중단 교체할 수 있다.
 */
@Configuration
@EnableConfigurationProperties({ GeminiProperties.class, GenreProperties.class, GenreClaudeProperties.class,
		CatalogEnrichProperties.class })
public class GenreConfig {

	/** 연결 수립 상한(읽기 타임아웃과 별개) — 세 수집 클라이언트가 같은 값을 쓴다. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	/**
	 * Gemini 전용 RestClient. baseUrl·읽기 타임아웃(워커 스레드 장기 점유·부팅 지연 방지)을 설정에서 주입한다.
	 * 응답의 여분 필드(role·thoughtSignature·usageMetadata 등)는 {@code GeminiDto} 응답 record의
	 * {@code @JsonIgnoreProperties(ignoreUnknown)}로 관대하게 파싱하므로 별도 컨버터 커스터마이즈는 두지 않는다.
	 * 요청선(경로·헤더·본문)은 {@link GeminiGenreClassifier}가 이 클라이언트로 직접 조립한다 —
	 * 선언형 HTTP Interface 프록시를 두지 않는다(공공데이터 전시 API와 같은 구조).
	 */
	@Bean
	public RestClient geminiRestClient(GeminiProperties properties) {
		// 연결 수립 상한 — 살아있는 상대는 1초 안에 붙는다. 팩토리엔 세터가 없어 HttpClient에 걸어 넘긴다.
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
		return RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}

	/**
	 * 주 장르 분류기 선택. {@code app.exhibition.genre.classifier=gemini}면 폴백 체인(Gemini→Claude), 그 외면
	 * 결정적 mock을 @Primary로 노출한다(기본 mock — 로컬/CI/키없음은 AI 호출·비용 0).
	 *
	 * <p>체인의 resilience4j는 <b>호출 내</b> 즉시 재시도·차단만 담당한다 — 재시작을 넘는 durable 재시도는
	 * 아웃박스 폴러의 몫이라 시도 횟수를 짧게 잡는다(2계층 분리 — ADR-10). 서킷브레이커는 연속 실패한 공급자를
	 * 60초 차단해, 죽은 1차에 매번 타임아웃을 태우지 않고 2차로 직행하게 한다.
	 */
	@Bean
	@Primary
	public GenreClassifier genreClassifier(GenreProperties properties,
			GeminiGenreClassifier geminiGenreClassifier, ClaudeGenreClassifier claudeGenreClassifier,
			MockGenreClassifier mockGenreClassifier) {
		if (!properties.useGemini()) {
			return mockGenreClassifier;
		}
		RetryConfig retryConfig = RetryConfig.custom()
				.maxAttempts(2) // 호출 내 1회만 즉시 재시도 — 그 이상은 아웃박스 durable 재시도가 담당
				.waitDuration(Duration.ofSeconds(2))
				.build();
		CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(4)
				.minimumNumberOfCalls(2)
				.failureRateThreshold(50)
				.waitDurationInOpenState(Duration.ofSeconds(60))
				.permittedNumberOfCallsInHalfOpenState(1)
				.build();
		return new FailoverGenreClassifier(geminiGenreClassifier, claudeGenreClassifier,
				Retry.of("genre-gemini", retryConfig), Retry.of("genre-claude", retryConfig),
				CircuitBreaker.of("genre-gemini", breakerConfig), CircuitBreaker.of("genre-claude", breakerConfig));
	}
}
