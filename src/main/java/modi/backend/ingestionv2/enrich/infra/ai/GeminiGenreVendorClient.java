package modi.backend.ingestionv2.enrich.infra.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;

import io.micrometer.observation.ObservationRegistry;
import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.exhibition.genre.GenreProvider;

/**
 * 1차 공급자(Gemini) - 무료 한도.
 *
 * <ul>
 *   <li>스타터 오토컨피그 미사용 - 키가 없으면 컨텍스트 기동을 실패시켜 "키 없으면 AI만 비활성" 계약을 깬다</li>
 *   <li>따옴표 없는 enum 값 하나를 그대로 돌려주는 전용 구조화 출력 모드를 쓴다</li>
 *   <li>SDK 재시도까지 더해지면 재시도 계층이 셋이 되어 워커가 오래 잡히므로 단일 시도로 고정</li>
 * </ul>
 */
@Order(1)
@Component
public class GeminiGenreVendorClient implements GenreVendorClient {

	/** 따옴표 없는 enum 값 하나를 그대로 돌려주는 Gemini 전용 구조화 출력 모드. */
	private static final String ENUM_MIME_TYPE = "text/x.enum";
	/** Gemini Developer API 버전. */
	private static final String API_VERSION = "v1beta";
	/** 재시도 없음 - 프레임워크 기본 RetryTemplate은 여러 번 재시도하므로 명시적으로 끈다. */
	private static final RetryTemplate SINGLE_ATTEMPT = new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());

	private final ExternalApiCallLogRecorder callLogRecorder;
	private final String model;
	/** api-key 미설정이면 null - 체인이 이 공급자를 건너뛴다. */
	private final ChatClient chatClient;

	public GeminiGenreVendorClient(ExternalApiCallLogRecorder callLogRecorder,
			ObservationRegistry observationRegistry,
			@Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
			@Value("${app.ai.gemini.api-key:}") String apiKey,
			@Value("${app.ai.gemini.model:gemini-2.5-flash}") String model,
			@Value("${app.ai.gemini.timeout-seconds:60}") long timeoutSeconds) {
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
		return GenreProvider.GEMINI;
	}

	@Override
	public String model() {
		return model;
	}

	@Override
	public String classify(String title, String description) {
		return GenreVendorSupport.call(chatClient, ExternalApi.GEMINI, model, title, description, callLogRecorder,
				response -> {
					String content = GenreVendorSupport.text(response);
					return content == null || content.isBlank() ? null : content.trim();
				});
	}

	private static ChatClient build(ObservationRegistry observationRegistry, String baseUrl, String apiKey,
			String model, long timeoutSeconds) {
		if (apiKey == null || apiKey.isBlank()) {
			return null;
		}
		Client genAiClient = Client.builder()
				.apiKey(apiKey)
				.httpOptions(HttpOptions.builder()
						.baseUrl(baseUrl)
						.apiVersion(API_VERSION)
						.timeout(Math.toIntExact(timeoutSeconds * 1000))
						.retryOptions(HttpRetryOptions.builder().attempts(1).build())
						.build())
				.build();
		GoogleGenAiChatModel chatModel = GoogleGenAiChatModel.builder()
				.genAiClient(genAiClient)
				.retryTemplate(SINGLE_ATTEMPT)
				.observationRegistry(observationRegistry)
				.build();
		return ChatClient.builder(chatModel)
				.defaultOptions(GoogleGenAiChatOptions.builder()
						.model(model)
						.responseMimeType(ENUM_MIME_TYPE)
						.responseSchema(GenreVendorSupport.enumSchema()))
				.build();
	}
}
