package modi.backend.ingestion.infra.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import org.springframework.ai.chat.client.ChatClient;
import modi.backend.ingestion.properties.GeminiProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * {@link GeminiClient} 실HTTP 계약 검증(MockWebServer). 실제 Gemini 대신 목 서버로 응답 포맷·구조화 요청·
 * <b>실패 시 예외(ADR-11 계약 반전)</b>를 확인한다 — 폴백값·내부 재시도는 없다(2차 전환은 폴백 체인,
 * durable 재시도는 아웃박스의 몫).
 *
 * <p>전송이 RestClient에서 Spring AI({@code GoogleGenAiChatModel} + google-genai SDK)로 바뀌었어도
 * <b>계약은 그대로</b>여야 한다 — 그래서 검증 항목을 바꾸지 않고 조립만 갈아끼웠다.
 */
class GeminiClientTest {

	/** 운영 조립(GenreConfig)과 같은 정책 — 재시도 없음(단일 시도 계약). */
	private static final RetryTemplate SINGLE_ATTEMPT = new RetryTemplate(
			RetryPolicy.builder().maxRetries(0).build());

	private MockWebServer server;
	private GeminiClient classifier;

	private final GenreClassification input = new GenreClassification(
			"모네에서 세잔까지 — 인상주의 특별전", "PAINTING", "인상주의 대표작 특별전", "예술의전당 한가람미술관", null, "전시");

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		classifier = classifierWith(new GeminiProperties(
				server.url("/").toString(), "test-api-key", "gemini-2.5-flash", 5L));
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	/** 운영(GenreConfig.geminiClient)과 같은 조립 — 목 서버를 겨냥하도록 SDK baseUrl만 바꾼다. */
	private GeminiClient classifierWith(GeminiProperties properties) {
		if (!properties.isConfigured()) {
			return new GeminiClient(null, properties, new SimpleMeterRegistry());
		}
		Client genAiClient = Client.builder()
				.apiKey(properties.apiKey())
				.httpOptions(HttpOptions.builder()
						.baseUrl(properties.baseUrl())
						.apiVersion("v1beta")
						.timeout(Math.toIntExact(properties.timeoutSeconds() * 1000))
						.retryOptions(HttpRetryOptions.builder().attempts(1).build())
						.build())
				.build();
		ChatModel chatModel = GoogleGenAiChatModel.builder()
				.genAiClient(genAiClient)
				.retryTemplate(SINGLE_ATTEMPT)
				.build();
		return new GeminiClient(ChatClient.create(chatModel), properties, new SimpleMeterRegistry());
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
		assertThat(server.getRequestCount()).isEqualTo(1); // 단일 시도 — 재시도는 이 계층의 책임이 아니다.
	}

	@Test
	@DisplayName("api-key 미설정이면 외부 호출 없이 분류 실패 예외를 던진다(체인이 2차로 전환)")
	void classify_notConfigured_throwsWithoutCall() {
		GeminiClient disabled = classifierWith(new GeminiProperties(
				server.url("/").toString(), "", "gemini-2.5-flash", 5L));

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
