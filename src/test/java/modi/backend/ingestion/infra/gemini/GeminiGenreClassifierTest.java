package modi.backend.ingestion.infra.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import modi.backend.ingestion.properties.GeminiProperties;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * {@link GeminiGenreClassifier} 실HTTP 계약 검증(MockWebServer). 실제 Gemini 대신 목 서버로
 * 응답 포맷·구조화 요청·<b>실패 시 예외(ADR-11 계약 반전)</b>를 확인한다 — 폴백값·내부 재시도는 이제 없다
 * (즉시 재시도·2차 전환은 폴백 체인, durable 재시도는 아웃박스의 몫).
 */
class GeminiGenreClassifierTest {

	private MockWebServer server;
	private GeminiGenreClassifier classifier;

	private final GenreClassification input = new GenreClassification(
			"모네에서 세잔까지 — 인상주의 특별전", "PAINTING", "인상주의 대표작 특별전", "예술의전당 한가람미술관", null, "전시");

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		classifier = classifierWith(new GeminiProperties(
				server.url("/").toString(), "test-api-key", "gemini-2.5-flash", 5L, 1, 0L));
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	private GeminiGenreClassifier classifierWith(GeminiProperties properties) {
		// 운영 조립(GenreConfig)과 동일하게 JDK 팩토리 고정 — 테스트 클래스패스의 Apache HttpClient5(Testcontainers 전이)가
		// 자동감지되면 429를 전송 계층에서 한 번 더 재시도해(DefaultHttpRequestRetryStrategy) 요청 수 검증이 깨진다.
		RestClient restClient = RestClient.builder().baseUrl(properties.baseUrl())
				.requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
				.build();
		GeminiApi api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
				.createClient(GeminiApi.class);
		return new GeminiGenreClassifier(api, properties, new SimpleMeterRegistry());
	}

	@Test
	@DisplayName("200 응답의 enum 값을 그대로 장르로 반환하고, 구조화 요청을 올바른 경로/헤더로 보낸다")
	void classify_success_returnsGenreAndSendsStructuredRequest() throws InterruptedException {
		server.enqueue(candidateResponse("사진"));

		GenreResult result = classifier.classify(input);

		assertThat(result.genreKeyword()).isEqualTo("사진");
		RecordedRequest recorded = server.takeRequest();
		assertThat(recorded.getPath()).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
		assertThat(recorded.getHeader("x-goog-api-key")).isEqualTo("test-api-key");
		String body = recorded.getBody().readUtf8();
		assertThat(body).contains("text/x.enum").contains("\"enum\"").contains("회화·드로잉");
	}

	@Test
	@DisplayName("성공 시 계보로 provider=GEMINI와 응답 modelVersion(요청 모델이 아니라)을 붙인다")
	void classify_success_carriesProviderAndResponseModelVersion() {
		// 요청 모델은 "gemini-2.5-flash"(별칭일 수 있음)인데 실제 서빙 모델은 응답이 말한 값이다 — 계보엔 응답 쪽이 남아야 한다.
		server.enqueue(candidateResponse("사진", "gemini-2.5-flash-002"));

		GenreResult result = classifier.classify(input);

		assertThat(result.provider()).isEqualTo(GenreProvider.GEMINI);
		assertThat(result.model()).isEqualTo("gemini-2.5-flash-002");
	}

	@Test
	@DisplayName("응답이 마스터에 없는 값이면 폴백값 대신 분류 실패 예외를 던진다(ADR-11)")
	void classify_unknownGenre_throws() {
		server.enqueue(candidateResponse("K-POP 콘서트"));

		// 가짜 값이 저장되는 순간 미분류 대상에서 영구 이탈하던 과거 문제 — 이제 실패는 값이 아니라 예외다.
		assertThatThrownBy(() -> classifier.classify(input))
				.isInstanceOf(GenreClassificationException.class)
				.hasMessageContaining("마스터에 없음");
	}

	@Test
	@DisplayName("429는 내부 재시도 없이 단일 시도로 예외를 던진다(재시도·전환은 체인·아웃박스의 몫)")
	void classify_rateLimited_throwsWithoutInternalRetry() {
		server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":{\"code\":429}}"));

		assertThatThrownBy(() -> classifier.classify(input))
				.isInstanceOf(GenreClassificationException.class);
		assertThat(server.getRequestCount()).isEqualTo(1); // 수동 429 백오프 루프가 제거됐다 — 단일 시도.
	}

	@Test
	@DisplayName("api-key 미설정이면 외부 호출 없이 분류 실패 예외를 던진다(체인이 2차로 전환)")
	void classify_notConfigured_throwsWithoutCall() {
		GeminiGenreClassifier disabled = classifierWith(new GeminiProperties(
				server.url("/").toString(), "", "gemini-2.5-flash", 5L, 1, 0L));

		assertThatThrownBy(() -> disabled.classify(input))
				.isInstanceOf(GenreClassificationException.class);
		assertThat(server.getRequestCount()).isZero();
	}

	/** 실제 Gemini 응답 형태를 모사한 200 응답(여분 필드 role·finishReason 포함 — 관대한 파싱 검증). */
	private static MockResponse candidateResponse(String genreText) {
		return candidateResponse(genreText, "gemini-2.5-flash");
	}

	/** 응답 modelVersion을 지정하는 변형 — "요청 모델이 아니라 응답 모델을 계보에 남긴다"를 검증하기 위함. */
	private static MockResponse candidateResponse(String genreText, String modelVersion) {
		String json = """
				{
				  "candidates": [
				    {
				      "content": { "role": "model", "parts": [ { "text": "%s" } ] },
				      "finishReason": "STOP",
				      "index": 0
				    }
				  ],
				  "modelVersion": "%s"
				}
				""".formatted(genreText, modelVersion);
		return new MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", "application/json")
				.setBody(json);
	}
}
