package modi.backend.ingestion.infra.ai;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.infra.ai.GeminiGenreDto.GenreAnswer;
import modi.backend.ingestion.infra.ai.GeminiGenreDto.GenreRequest;
import modi.backend.ingestion.properties.GeminiProperties;

/**
 * Gemini(무료 한도) 기반 장르 분류기 — 폴백 체인의 <b>1차</b> 공급자(ADR-11).
 *
 * <p><b>받은 요청을 보내고 받는 데까지가 전부다</b>({@code GooglePlaceClient}와 같은 모양). 지시·허용 집합·분류
 * 대상은 코어가 {@link GenreClassificationRequest}로 정해 내려보내고, 벤더 규격으로의 번역은
 * {@link GenreRequest}가, 응답을 도메인 값으로 되돌리는 일은 {@link GenreAnswer}가 맡는다.
 * <b>어떤 모델로 어떻게 부를지는 이 클래스가 정한다</b> — 모델명·구조화 출력({@code text/x.enum} + 허용 집합
 * 스키마)을 {@link ChatClient} 기본 옵션으로 심는다. {@code AiModelConfig}에서 주입받는 {@code ChatModel}은
 * <b>연결만</b>(baseUrl·키·타임아웃·재시도) 안다. 기능 설정을 인프라 config에 두지 않는 것이 이 배치의 이유다
 * ({@code GooglePlaceClient}가 RestClient 빈을 받아 FieldMask·질의를 스스로 정하는 것과 같은 모양).
 *
 * <p><b>계약 반전(ADR-11)</b>: 유효한 분류를 만들지 못하면 {@link GenreClassificationException}을 던진다.
 * 2차 공급자(Claude) 전환은 폴백 체인({@code FailoverGenreClassifier})이, 재시작을 넘는 durable 재시도는
 * 아웃박스 폴러가 맡는다. 이 클래스는 <b>단일 시도</b>만 한다.
 *
 * <p>호출 관측(토큰·지연·에러율)은 Spring AI 내장({@code gen_ai.client.*})에 맡긴다 — 손수 세지 않는다.
 *
 * <p><b>배치 분류는 없다</b> — 장르는 draft당 개별 분류다(CLAUDE.md). 재도입하지 마라.
 */
@Component
public class GeminiClient implements GenreClassifier {

	/** 따옴표 없는 enum 값 하나를 그대로 돌려주는 Gemini 전용 구조화 출력 모드. */
	private static final String ENUM_MIME_TYPE = "text/x.enum";

	private final ChatClient chatClient; // api-key 미설정 시 null(호출 시 예외 — 체인이 2차로 전환)

	public GeminiClient(@Nullable GoogleGenAiChatModel genreGeminiChatModel, GeminiProperties properties) {
		this.chatClient = genreGeminiChatModel == null ? null
				: ChatClient.builder(genreGeminiChatModel)
						.defaultOptions(GoogleGenAiChatOptions.builder()
								.model(properties.model())
								.responseMimeType(ENUM_MIME_TYPE)
								.responseSchema(enumSchema()))
						.build();
	}

	/**
	 * 허용 장르 enum 스키마(JSON). Spring AI의 {@code responseSchema}는 문자열이고 google-genai SDK의
	 * {@code responseJsonSchema}로 넘어가므로 Gemini 고유 스키마가 아니라 <b>JSON Schema 표기</b>로 쓴다.
	 * 분류는 draft당 1건이라 배열이 아니라 단일 문자열 제약이다.
	 */
	private static String enumSchema() {
		return JsonMapper.builder().build()
				.writeValueAsString(Map.of("type", "string", "enum", GenreKeyword.all()));
	}

	/**
	 * 장르 분류 <b>단일 시도</b> — 설정 확인 → 메시지로 옮김 → 호출 → 응답이 도메인 값으로.
	 * 재시도·2차 전환은 폴백 체인·아웃박스의 몫이라 여기서 하지 않는다.
	 */
	@Override
	public GenreResult classify(GenreClassificationRequest request) {
		if (chatClient == null) {
			throw new GenreClassificationException("Gemini api-key 미설정 — 장르 분류 불가(체인이 2차로 전환)");
		}

		GenreRequest vendorRequest = new GenreRequest(request);

		// 전송·HTTP 오류는 분류 실패로 감싸 던진다(429도 여기로; 2차 전환은 체인이 한다).
		ChatResponse response;
		try {
			response = chatClient.prompt()
					.system(vendorRequest.systemMessage())
					.user(vendorRequest.userMessage())
					.call()
					.chatResponse();
		} catch (RuntimeException e) {
			throw new GenreClassificationException("Gemini 장르 분류 호출 실패: " + e.getMessage(), e);
		}

		return GenreAnswer.from(response).toGenreResult(request);
	}
}
