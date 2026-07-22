package modi.backend.ingestion.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import modi.backend.ingestion.infra.ai.OpenAiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.observation.ObservationRegistry;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.config.AiModelConfig;
import modi.backend.ingestion.properties.GenreOpenAiProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * {@link OpenAiClient} 실HTTP 계약 검증(MockWebServer). 실제 OpenAI 대신 목 서버로 응답 포맷·구조화 요청·
 * <b>실패 시 예외(ADR-11 계약 반전)</b>를 확인한다. 1차({@code GeminiClientTest})와 검증 항목이 같고,
 * <b>응답 본문 모양만 다르다</b> — OpenAI엔 {@code text/x.enum}이 없어 {@code {"genre":"..."}} JSON으로 받는다.
 */
class OpenAiClientTest {

	private MockWebServer server;
	private OpenAiClient classifier;

	private final GenreClassification subject = new GenreClassification(
			"모네에서 세잔까지 — 인상주의 특별전", "PAINTING", "인상주의 대표작 특별전", "예술의전당 한가람미술관", null, "전시");

	/** 운영 서비스와 같은 방식으로 요청을 조립한다 — 지시·허용 집합은 호출부가 정해 넘긴다. */
	private final GenreClassificationRequest request = new GenreClassificationRequest(
			GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText());

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		classifier = classifierWith(new GenreOpenAiProperties(
				server.url("/v1").toString(), "test-api-key", "gpt-5.4-nano", 5L));
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	/** 운영과 같은 조립을 그대로 부른다(연결은 AiModelConfig, 옵션은 어댑터) — baseUrl만 목 서버로 바꾼다. */
	private OpenAiClient classifierWith(GenreOpenAiProperties properties) {
		return new OpenAiClient(
				new AiModelConfig().genreOpenAiChatModel(properties, ObservationRegistry.NOOP), properties);
	}

	@Test
	@DisplayName("JSON 응답의 genre를 장르로 반환하고, 구조화 요청을 Bearer 인증과 함께 보낸다")
	void classify_success_returnsGenreAndSendsStructuredRequest() throws InterruptedException {
		server.enqueue(completion("회화·드로잉"));

		GenreResult result = classifier.classify(request);

		assertThat(result.genreKeyword()).isEqualTo("회화·드로잉");
		RecordedRequest recorded = server.takeRequest();
		assertThat(recorded.getPath()).endsWith("/chat/completions");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
		String body = recorded.getBody().readUtf8();
		assertThat(body).contains("json_schema").contains("회화·드로잉"); // 허용 집합이 스키마로 나간다
	}

	@Test
	@DisplayName("성공 시 계보로 provider=OPENAI와 응답 model(요청 모델이 아니라)을 붙인다")
	void classify_success_carriesProviderAndResponseModel() {
		// 요청 모델은 "gpt-5.4-nano"(별칭일 수 있음)인데 실제 서빙 모델은 응답이 말한 값이다.
		server.enqueue(completion("사진", "gpt-5.4-nano-2026-03-17"));

		GenreResult result = classifier.classify(request);

		assertThat(result.provider()).isEqualTo(GenreProvider.OPENAI);
		assertThat(result.model()).isEqualTo("gpt-5.4-nano-2026-03-17");
	}

	@Test
	@DisplayName("응답이 허용 집합에 없는 값이면 폴백값 대신 분류 실패 예외를 던진다(ADR-11)")
	void classify_unknownGenre_throws() {
		server.enqueue(completion("K-POP 콘서트"));

		assertThatThrownBy(() -> classifier.classify(request))
				.isInstanceOf(GenreClassificationException.class)
				.hasMessageContaining("허용 집합에 없음");
	}

	@Test
	@DisplayName("본문이 JSON이 아니면(형식 이탈) 분류 실패 예외를 던진다")
	void classify_nonJsonBody_throws() {
		server.enqueue(completionRaw("회화·드로잉")); // 스키마를 어기고 평문으로 온 경우

		assertThatThrownBy(() -> classifier.classify(request))
				.isInstanceOf(GenreClassificationException.class)
				.hasMessageContaining("허용 집합에 없음");
	}

	@Test
	@DisplayName("429는 내부 재시도 없이 단일 시도로 예외를 던진다(재시도는 아웃박스의 몫)")
	void classify_rateLimited_throwsWithoutInternalRetry() {
		server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":{\"code\":\"rate_limit\"}}"));

		assertThatThrownBy(() -> classifier.classify(request))
				.isInstanceOf(GenreClassificationException.class);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("api-key 미설정이면 외부 호출 없이 분류 실패 예외를 던진다")
	void classify_notConfigured_throwsWithoutCall() {
		OpenAiClient disabled = classifierWith(new GenreOpenAiProperties(
				server.url("/v1").toString(), "", "gpt-5.4-nano", 5L));

		assertThatThrownBy(() -> disabled.classify(request))
				.isInstanceOf(GenreClassificationException.class);
		assertThat(server.getRequestCount()).isZero();
	}

	/** 구조화 출력 규격대로 {@code {"genre":"..."}}를 담은 chat completion 응답. */
	private static MockResponse completion(String genre) {
		return completion(genre, "gpt-5.4-nano");
	}

	private static MockResponse completion(String genre, String model) {
		return completionBody("{\\\"genre\\\":\\\"" + genre + "\\\"}", model);
	}

	/** 스키마를 어기고 평문으로 온 응답(형식 이탈 검증용). */
	private static MockResponse completionRaw(String rawText) {
		return completionBody(rawText, "gpt-5.4-nano");
	}

	private static MockResponse completionBody(String content, String model) {
		String json = """
				{
				  "id": "chatcmpl-test",
				  "object": "chat.completion",
				  "created": 1770000000,
				  "model": "%s",
				  "choices": [
				    {
				      "index": 0,
				      "message": { "role": "assistant", "content": "%s" },
				      "finish_reason": "stop"
				    }
				  ],
				  "usage": { "prompt_tokens": 120, "completion_tokens": 8, "total_tokens": 128 }
				}
				""".formatted(model, content);
		return new MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", "application/json")
				.setBody(json);
	}
}
