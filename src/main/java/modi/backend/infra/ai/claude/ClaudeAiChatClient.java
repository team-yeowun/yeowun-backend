package modi.backend.infra.ai.claude;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.models.messages.Usage;

import io.micrometer.core.instrument.MeterRegistry;
import modi.backend.config.AiProperties;
import modi.backend.domain.ai.AiChatClient;
import modi.backend.domain.ai.AiErrorCode;
import modi.backend.support.error.CoreException;

/**
 * {@link AiChatClient}의 Claude(Anthropic) 어댑터. 공식 anthropic-java SDK로 Messages API 호출.
 * api-key 미설정이면 클라이언트를 만들지 않고, 호출 시 {@link AiErrorCode#AI_DISABLED}를 던진다
 * (AI만 비활성, 나머지 기능은 정상). 모델·max-tokens·타임아웃은 {@link AiProperties} 설정값을 사용한다.
 * 응답마다 토큰 사용량을 로깅 + Micrometer 카운터로 기록한다(비용/스파이크 모니터링).
 * 구조화 출력({@code completeStructured})은 SDK의 output_config(스키마 강제)를 사용한다.
 */
@Component
public class ClaudeAiChatClient implements AiChatClient {

	private static final Logger log = LoggerFactory.getLogger(ClaudeAiChatClient.class);

	private final AiProperties properties;
	private final MeterRegistry meterRegistry;
	private final AnthropicClient client; // api-key 미설정 시 null

	public ClaudeAiChatClient(AiProperties properties, MeterRegistry meterRegistry) {
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		this.client = properties.isConfigured()
				? AnthropicOkHttpClient.builder()
						.apiKey(properties.apiKey())
						.timeout(Duration.ofSeconds(properties.timeoutSeconds()))
						.build()
				: null;
	}

	@Override
	public String complete(String systemPrompt, String userPrompt) {
		requireEnabled();
		try {
			Message response = client.messages().create(baseParams(systemPrompt, userPrompt).build());
			record(response.usage());
			return response.content().stream()
					.flatMap(block -> block.text().stream())
					.map(text -> text.text())
					.collect(Collectors.joining("\n"))
					.trim();
		} catch (CoreException e) {
			throw e;
		} catch (Exception e) {
			throw new CoreException(AiErrorCode.AI_GENERATION_FAILED, e.getMessage());
		}
	}

	@Override
	public void completeStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
		requireEnabled();
		// createStreaming — SSE로 content_block_delta(text) 이벤트를 순차 수신해 델타를 흘려보낸다.
		// 스트리밍 경로는 응답이 조각으로 오므로 usage(토큰) 집계는 생략한다(모니터링은 non-stream 경로에서 수행).
		try (StreamResponse<RawMessageStreamEvent> stream = client.messages()
				.createStreaming(baseParams(systemPrompt, userPrompt).build())) {
			stream.stream().forEach(event -> event.contentBlockDelta()
					.map(RawContentBlockDeltaEvent::delta)
					.flatMap(RawContentBlockDelta::text)
					.map(TextDelta::text)
					.filter(text -> !text.isEmpty())
					.ifPresent(onDelta));
		} catch (CoreException e) {
			throw e;
		} catch (Exception e) {
			throw new CoreException(AiErrorCode.AI_GENERATION_FAILED, e.getMessage());
		}
	}

	@Override
	public <T> T completeStructured(String systemPrompt, String userPrompt, Class<T> schemaType) {
		requireEnabled();
		try {
			StructuredMessageCreateParams<T> params = baseParams(systemPrompt, userPrompt)
					.outputConfig(schemaType)
					.build();
			var response = client.messages().create(params);
			record(response.usage());
			return response.content().stream()
					.flatMap(block -> block.text().stream())
					.map(text -> text.text())
					.findFirst()
					.orElseThrow(() -> new CoreException(AiErrorCode.AI_GENERATION_FAILED, "구조화 응답이 비어 있습니다."));
		} catch (CoreException e) {
			throw e;
		} catch (Exception e) {
			throw new CoreException(AiErrorCode.AI_GENERATION_FAILED, e.getMessage());
		}
	}

	private MessageCreateParams.Builder baseParams(String systemPrompt, String userPrompt) {
		return MessageCreateParams.builder()
				.model(properties.model())
				.maxTokens(properties.maxTokens())
				.system(systemPrompt)
				.addUserMessage(userPrompt);
	}

	private void requireEnabled() {
		if (client == null) {
			throw new CoreException(AiErrorCode.AI_DISABLED);
		}
	}

	/** 토큰 사용량 로깅 + Micrometer 카운터 기록. 실패는 무시(본 응답에 영향 없음). */
	private void record(Usage usage) {
		try {
			long input = usage.inputTokens();
			long output = usage.outputTokens();
			log.info("AI 호출 사용량 model={} input={} output={}", properties.model(), input, output);
			meterRegistry.counter("modi.ai.calls", "model", properties.model()).increment();
			meterRegistry.counter("modi.ai.tokens", "model", properties.model(), "type", "input").increment(input);
			meterRegistry.counter("modi.ai.tokens", "model", properties.model(), "type", "output").increment(output);
		} catch (Exception ignored) {
			// 모니터링은 부가 기능 — 실패해도 응답 처리엔 영향 없음
		}
	}
}
