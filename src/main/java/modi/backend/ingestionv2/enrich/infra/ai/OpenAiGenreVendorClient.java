package modi.backend.ingestionv2.enrich.infra.ai;

import java.time.Duration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;

import io.micrometer.observation.ObservationRegistry;
import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.exhibition.genre.GenreProvider;
import tools.jackson.databind.JsonNode;

/**
 * 2차 공급자(OpenAI) - 장르 전용 버킷(감상문 한도와 분리).
 *
 * <ul>
 *   <li>단일 값 구조화 출력 모드가 없어 객체 한 겹을 씌운다</li>
 *   <li>SDK 재시도를 끈다 - 재시도는 배달 계층이 제공</li>
 * </ul>
 */
@Order(2)
@Component
public class OpenAiGenreVendorClient implements GenreVendorClient {

	/** 객체 한 겹의 필드명. */
	private static final String GENRE_FIELD = "genre";

	private final ExternalApiCallLogRecorder callLogRecorder;
	private final String model;
	/** api-key 미설정이면 null - 체인이 이 공급자를 건너뛴다. */
	private final ChatClient chatClient;

	public OpenAiGenreVendorClient(ExternalApiCallLogRecorder callLogRecorder,
			ObservationRegistry observationRegistry,
			@Value("${app.exhibition.genre.openai.base-url:https://api.openai.com/v1}") String baseUrl,
			@Value("${app.exhibition.genre.openai.api-key:}") String apiKey,
			@Value("${app.exhibition.genre.openai.model:gpt-5.4-nano}") String model,
			@Value("${app.exhibition.genre.openai.timeout-seconds:60}") long timeoutSeconds) {
		this.callLogRecorder = callLogRecorder;
		this.model = model;
		this.chatClient = build(observationRegistry, baseUrl, apiKey, model, timeoutSeconds);
	}

	@Override
	public boolean isConfigured() {
		return chatClient != null;
	}

	@Override
	public GenreProvider provider() {
		return GenreProvider.OPENAI;
	}

	@Override
	public String model() {
		return model;
	}

	@Override
	public String classify(String title, String description) {
		return GenreVendorSupport.call(chatClient, ExternalApi.OPENAI, model, title, description, callLogRecorder,
				response -> {
					String content = GenreVendorSupport.text(response);
					if (content == null || content.isBlank()) {
						return null;
					}
					return jsonGenre(content);
				});
	}

	private static String jsonGenre(String content) {
		try {
			JsonNode node = GenreVendorSupport.JSON.readTree(content).path(GENRE_FIELD);
			return node.isString() ? node.stringValue().trim() : null;
		} catch (RuntimeException malformed) {
			return null;
		}
	}

	private static ChatClient build(ObservationRegistry observationRegistry, String baseUrl, String apiKey,
			String model, long timeoutSeconds) {
		if (apiKey == null || apiKey.isBlank()) {
			return null;
		}
		Duration timeout = Duration.ofSeconds(timeoutSeconds);
		OpenAIClient sync = OpenAIOkHttpClient.builder()
				.apiKey(apiKey)
				.baseUrl(baseUrl)
				.timeout(timeout)
				.maxRetries(0)
				.build();
		// 스트리밍을 쓰지 않아도 비동기 클라이언트가 빌드 시점에 필요하다(없으면 환경변수로 자체 생성하다 실패).
		OpenAIClientAsync async = OpenAIOkHttpClientAsync.builder()
				.apiKey(apiKey)
				.baseUrl(baseUrl)
				.timeout(timeout)
				.maxRetries(0)
				.build();
		OpenAiChatModel chatModel = OpenAiChatModel.builder()
				.openAiClient(sync)
				.openAiClientAsync(async)
				.observationRegistry(observationRegistry)
				.build();
		return ChatClient.builder(chatModel)
				.defaultOptions(OpenAiChatOptions.builder()
						.model(model)
						.responseFormat(OpenAiChatModel.ResponseFormat.builder()
								.type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
								.jsonSchema(GenreVendorSupport.objectSchema(GENRE_FIELD))
								.build()))
				.build();
	}
}
