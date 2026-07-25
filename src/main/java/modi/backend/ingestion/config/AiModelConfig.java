package modi.backend.ingestion.config;

import java.time.Duration;

import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;

import io.micrometer.observation.ObservationRegistry;
import modi.backend.ingestion.properties.GeminiProperties;
import modi.backend.ingestion.properties.GenreOpenAiProperties;

/**
 * AI 공급자 <b>연결</b> 빈 등록 — 벤더에 붙는 일(SDK 클라이언트·baseUrl·키·타임아웃·재시도 정책)만 한다.
 *
 * <p><b>여기 기능 설정을 두지 마라.</b> 어떤 모델로 무엇을 물어볼지(모델명·프롬프트·구조화 출력)는 기능의 결정이라
 * 각 어댑터가 자기 {@code ChatClient}를 만들며 정한다({@code GeminiClient}·{@code OpenAiClient}). 이 파일이 기능을
 * 알기 시작하면 AI 기능이 늘 때마다 여기가 커진다 — 그게 이 분리의 이유다.
 *
 * <p><b>빈 이름에 기능을 접두어로 붙인다</b>({@code genreGeminiChatModel}). 연결 설정은 기능마다 다를 수 있기
 * 때문이다 — 실제로 감상문 경로({@code app.ai.*})와 장르 경로({@code app.exhibition.genre.*})는 키·타임아웃 버킷을
 * 일부러 분리해 두었다(장르 백필이 감상문 한도를 잠식하지 않게). 공급자당 빈 하나를 공유하면 그 분리가 무너진다.
 * 새 기능이 같은 공급자를 다른 설정으로 쓰면 여기에 빈을 하나 더 추가한다.
 *
 * <p>스타터 오토컨피그를 쓰지 않는 이유: api-key가 없으면 컨텍스트 기동 자체를 실패시켜
 * ("Incomplete Google GenAI configuration") "키 없으면 AI만 비활성, 나머지는 정상"이라는 계약을 깬다.
 * 여기서는 키가 없으면 {@code null}을 돌려주고, 어댑터가 호출 시점에 분류 실패 예외를 던진다.
 */
@Configuration
@EnableConfigurationProperties({ GeminiProperties.class, GenreOpenAiProperties.class })
public class AiModelConfig {

	/** Gemini Developer API 버전 — 기존 RestClient가 쓰던 {@code /v1beta} 경로를 그대로 유지한다. */
	private static final String API_VERSION = "v1beta";

	/**
	 * 재시도 없음(ADR-11) — 즉시 재시도는 폴백 체인이, 재시작을 넘는 재시도는 아웃박스가 한다.
	 * 프레임워크 기본 {@code RetryTemplate}은 여러 번 재시도하므로 명시적으로 끈다.
	 */
	private static final RetryTemplate SINGLE_ATTEMPT = new RetryTemplate(
			RetryPolicy.builder().maxRetries(0).build());

	/**
	 * 전시 장르 분류 1차 공급자(Gemini) 연결. 키 미설정이면 {@code null} — 어댑터가 호출 시점에 실패로 처리한다.
	 */
	@Bean
	public GoogleGenAiChatModel genreGeminiChatModel(GeminiProperties properties,
			ObservationRegistry observationRegistry) {
		if (!properties.isConfigured()) {
			return null;
		}
		Client genAiClient = Client.builder()
				.apiKey(properties.apiKey())
				.httpOptions(HttpOptions.builder()
						.baseUrl(properties.baseUrl())
						.apiVersion(API_VERSION)
						// 응답 상한(워커 스레드 장기 점유·부팅 지연 방지) — SDK는 밀리초를 받는다.
						.timeout(Math.toIntExact(properties.timeoutSeconds() * 1000))
						// SDK도 자체 재시도를 한다 — 429를 ~20초 백오프 후 한 번 더 쏘는 것을 실측했다.
						// 재시도 계층이 셋(SDK·Spring AI·폴백 체인)이 되면 워커가 오래 잡히고 계층 분리가 깨진다.
						.retryOptions(HttpRetryOptions.builder().attempts(1).build())
						.build())
				.build();
		return GoogleGenAiChatModel.builder()
				.genAiClient(genAiClient)
				.retryTemplate(SINGLE_ATTEMPT)
				.observationRegistry(observationRegistry)
				.build();
	}

	/**
	 * 전시 장르 분류 2차 공급자(OpenAI) 연결. 키 미설정이면 {@code null}.
	 * <p>
	 * 동기 클라이언트만 주면 {@code OpenAiChatModel}이 비동기용을 환경변수({@code OPENAI_API_KEY})로 스스로 만들다
	 * 실패한다("At least one credential source must be specified") — 스트리밍을 쓰지 않아도 빌드 시점에 필요하다.
	 */
	@Bean
	public OpenAiChatModel genreOpenAiChatModel(GenreOpenAiProperties properties,
			ObservationRegistry observationRegistry) {
		if (!properties.isConfigured()) {
			return null;
		}
		Duration timeout = Duration.ofSeconds(properties.timeoutSeconds());
		OpenAIClient sync = OpenAIOkHttpClient.builder()
				.apiKey(properties.apiKey())
				.baseUrl(properties.baseUrl())
				.timeout(timeout)
				.maxRetries(0) // SDK 재시도 없음 — 재시도는 아웃박스의 몫이다(ADR-11)
				.build();
		OpenAIClientAsync async = OpenAIOkHttpClientAsync.builder()
				.apiKey(properties.apiKey())
				.baseUrl(properties.baseUrl())
				.timeout(timeout)
				.maxRetries(0)
				.build();
		// OpenAiChatModel엔 retryTemplate 훅이 없다 — 재시도는 위 SDK maxRetries=0으로 껐다.
		return OpenAiChatModel.builder()
				.openAiClient(sync)
				.openAiClientAsync(async)
				.observationRegistry(observationRegistry)
				.build();
	}
}
