package modi.backend.ingestionv2.enrich.infra.ai;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.ingestionv2.common.IngestionClock;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공급자 구현이 공유하는 호출 절차.
 *
 * <ul>
 *   <li>지시·허용 집합은 코어 소유(GenreInstruction·GenreKeyword) - 공급자가 바뀌어도 분류의 정의는 불변</li>
 *   <li>호출 직후 감사 1행 - 모델·429 추이가 곧 폴백 원인</li>
 *   <li>허용 집합 밖 응답은 폴백값이 아니라 실패 - 구조화 출력이 강제해도 한 번 더 확인한다</li>
 * </ul>
 */
final class GenreVendorSupport {

	/** 감사 request_key 컬럼 상한. */
	private static final int REQUEST_KEY_MAX = 500;

	static final JsonMapper JSON = JsonMapper.builder().build();

	private GenreVendorSupport() {
	}

	/** 시스템 지시 - 코어가 정한 지시에 허용 목록을 덧붙인다. */
	static String systemMessage() {
		return GenreInstruction.STANDARD + "%n장르 목록: %s".formatted(String.join(", ", GenreKeyword.all()));
	}

	/** 분류 대상 프롬프트 - 코어의 분류 어휘를 그대로 쓴다. */
	static String subjectOf(String title, String description) {
		return new GenreClassification(title, null, description, null, null, null).toPromptText();
	}

	/**
	 * 공급자 단일 시도. 호출·감사·허용 검증을 한 자리에 모은다.
	 *
	 * @param extractor 응답에서 장르 값을 꺼내는 방법(공급자마다 응답 모양이 다르다)
	 */
	static String call(ChatClient chatClient, ExternalApi api, String model, String title, String description,
			ExternalApiCallLogRecorder recorder, java.util.function.Function<ChatResponse, String> extractor) {
		String requestKey = requestKey(title);
		LocalDateTime calledAt = IngestionClock.now();
		ChatResponse response;
		try {
			response = chatClient.prompt()
					.system(systemMessage())
					.user(subjectOf(title, description))
					.call()
					.chatResponse();
		} catch (RuntimeException failure) {
			record(recorder, api, model, requestKey, outcomeOf(failure), calledAt);
			throw new GenreClassificationException(api + " 장르 분류 호출 실패: " + failure.getMessage(), failure);
		}
		String keyword = extractor.apply(response);
		if (!GenreKeyword.contains(keyword)) {
			// 안전필터로 후보가 통째로 빈 응답도 여기로 합류한다.
			record(recorder, api, model, requestKey, ExternalApiOutcome.NO_DATA, calledAt);
			throw new GenreClassificationException(api + " 장르 응답이 허용 집합에 없음: " + keyword);
		}
		record(recorder, api, model, requestKey, ExternalApiOutcome.SUCCESS, calledAt);
		return keyword;
	}

	/** 응답 본문 문자열. 없으면 null로 흘려 허용 검증에서 같은 실패로 합류한다. */
	static String text(ChatResponse response) {
		if (response == null || response.getResult() == null) {
			return null;
		}
		Generation generation = response.getResult();
		AssistantMessage output = generation.getOutput();
		return output == null ? null : output.getText();
	}

	/** 허용 장르 enum 스키마 - 분류는 건당 1개라 배열이 아니라 단일 문자열 제약. */
	static String enumSchema() {
		return JSON.writeValueAsString(Map.of("type", "string", "enum", GenreKeyword.all()));
	}

	/** OpenAI strict 모드 스키마 - 객체 한 겹 + 허용 집합(additionalProperties:false는 strict 요건). */
	static String objectSchema(String field) {
		return JSON.writeValueAsString(Map.of(
				"type", "object",
				"properties", Map.of(field, Map.of("type", "string", "enum", GenreKeyword.all())),
				"required", List.of(field),
				"additionalProperties", false));
	}

	/** 감사 대상 식별 - 어떤 전시를 물었는지(진행 행당 개별 분류라 제목이 곧 대상). */
	private static String requestKey(String title) {
		if (title == null) {
			return null;
		}
		return title.length() <= REQUEST_KEY_MAX ? title : title.substring(0, REQUEST_KEY_MAX);
	}

	/** 한도 초과(429)는 별도 결말 - 이 값의 추이가 곧 폴백 비율이다. */
	private static ExternalApiOutcome outcomeOf(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			String message = cause.getMessage();
			if (message == null) {
				continue;
			}
			String normalized = message.toLowerCase(Locale.ROOT);
			if (normalized.contains("429") || normalized.contains("resource_exhausted")
					|| normalized.contains("rate limit")) {
				return ExternalApiOutcome.RATE_LIMITED;
			}
		}
		return ExternalApiOutcome.FAILED;
	}

	private static void record(ExternalApiCallLogRecorder recorder, ExternalApi api, String model, String requestKey,
			ExternalApiOutcome outcome, LocalDateTime calledAt) {
		recorder.record(ExternalApiCallLog.ai(ApiCallSource.INGESTION, api, model, requestKey, outcome, calledAt));
	}
}
