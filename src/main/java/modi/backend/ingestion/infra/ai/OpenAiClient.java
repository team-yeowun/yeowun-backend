package modi.backend.ingestion.infra.ai;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.infra.ai.OpenAiGenreDto.GenreAnswer;
import modi.backend.ingestion.infra.ai.OpenAiGenreDto.GenreRequest;
import modi.backend.ingestion.properties.GenreOpenAiProperties;

/**
 * OpenAI 기반 장르 분류기 — 폴백 체인의 <b>2차</b> 공급자(ADR-11). 1차(Gemini)가 한도 초과·장애로 막혔을 때
 * 체인({@code FailoverGenreClassifier})이 여기로 전환한다.
 *
 * <p><b>1차와 같은 모양이다</b>({@code GeminiClient}) — 지시·허용 집합·분류 대상은 코어가
 * {@link GenreClassificationRequest}로 정해 내려보내고, 메시지 슬롯으로 옮기는 일은 {@link GenreRequest}가,
 * 응답을 도메인 값으로 되돌리는 일은 {@link GenreAnswer}가 맡는다. <b>어떤 모델로 어떻게 부를지는 이 클래스가
 * 정하고</b>({@link ChatClient} 기본 옵션), {@code AiModelConfig}에서 받는 {@code ChatModel}은 연결만 안다.
 * 두 공급자가 같은 {@code ChatClient} API 위에 서기 때문에 provider가 달라도 흐름은 1차와 동일하다.
 *
 * <p>감상문/리마인드의 {@code infra/ai}와는 별개 경로·별개 설정 버킷({@code app.exhibition.genre.openai.*})이다 —
 * 장르 백필이 감상문 한도를 잠식하지 않게(1차 Gemini와 같은 방침).
 *
 * <p><b>계약 반전(ADR-11)</b>: 유효한 분류를 만들지 못하면 {@link GenreClassificationException}을 던진다.
 * 이 클래스는 <b>단일 시도</b>만 하고, 재시도는 아웃박스가 durable하게 잇는다.
 *
 * <p>호출 관측(토큰·지연·에러율)은 Spring AI 내장({@code gen_ai.client.*})에 맡긴다 — 손수 세지 않는다.
 */
@Component
public class OpenAiClient implements GenreClassifier {

	private final ChatClient chatClient; // api-key 미설정 시 null(호출 시 예외 — 아웃박스가 재시도로 잇는다)

	public OpenAiClient(@Nullable OpenAiChatModel genreOpenAiChatModel, GenreOpenAiProperties properties) {
		this.chatClient = genreOpenAiChatModel == null ? null
				: ChatClient.builder(genreOpenAiChatModel)
						.defaultOptions(OpenAiChatOptions.builder()
								.model(properties.model())
								.responseFormat(OpenAiChatModel.ResponseFormat.builder()
										.type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
										.jsonSchema(objectSchema())
										.build()))
						.build();
	}

	/**
	 * OpenAI 구조화 출력 스키마. {@code text/x.enum} 같은 단일 값 모드가 없어 <b>객체 한 겹</b>을 씌우고
	 * {@code genre} 필드에 허용 집합을 못 박는다({@code additionalProperties:false} — strict 모드 요건).
	 */
	private static String objectSchema() {
		return JsonMapper.builder().build().writeValueAsString(Map.of(
				"type", "object",
				"properties", Map.of(OpenAiGenreDto.GENRE_FIELD,
						Map.of("type", "string", "enum", GenreKeyword.all())),
				"required", List.of(OpenAiGenreDto.GENRE_FIELD),
				"additionalProperties", false));
	}

	/**
	 * 장르 분류 <b>단일 시도</b> — 설정 확인 → 메시지로 옮김 → 호출 → 응답이 도메인 값으로.
	 * 재시도는 아웃박스의 몫이라 여기서 하지 않는다.
	 */
	@Override
	public GenreResult classify(GenreClassificationRequest request) {
		if (chatClient == null) {
			throw new GenreClassificationException("OpenAI(장르) api-key 미설정 — 2차 전환 불가");
		}

		GenreRequest vendorRequest = new GenreRequest(request);

		// 전송·HTTP 오류는 분류 실패로 감싸 던진다(429도 여기로; 전 공급자 실패는 아웃박스가 재시도한다).
		ChatResponse response;
		try {
			response = chatClient.prompt()
					.system(vendorRequest.systemMessage())
					.user(vendorRequest.userMessage())
					.call()
					.chatResponse();
		} catch (RuntimeException e) {
			throw new GenreClassificationException("OpenAI 장르 분류 호출 실패: " + e.getMessage(), e);
		}

		return GenreAnswer.from(response).toGenreResult(request);
	}
}
