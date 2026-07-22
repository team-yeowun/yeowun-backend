package modi.backend.ingestion.infra.gemini;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.lang.Nullable;

import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * Gemini(무료 한도) 기반 장르 분류기 — 폴백 체인의 <b>1차</b> 공급자(ADR-11).
 *
 * <p><b>여기 있는 것은 장르 분류라는 판단뿐이다</b>: 전시 정보를 보내고 → 돌아온 값이 마스터에 있는지 보고 →
 * 계보를 붙여 돌려준다. 모델·구조화 출력 옵션·시스템 프롬프트 같은 "어떻게 부를지"는 전부
 * {@code GenreConfig}가 {@link ChatClient}에 미리 심어 둔다 — 이 클래스는 무엇을 물어보고 무엇을 받아들일지만 안다.
 *
 * <p><b>계약 반전(ADR-11)</b>: 유효한 분류를 만들지 못하면 {@link GenreClassificationException}을 던진다.
 * 2차 공급자(Claude) 전환은 폴백 체인({@code FailoverGenreClassifier})이, 재시작을 넘는 durable 재시도는
 * 아웃박스 폴러가 맡는다. 이 클래스는 <b>단일 시도</b>만 한다.
 *
 * <p>호출 관측(토큰·지연·에러율)은 Spring AI 내장({@code gen_ai.client.*})에 맡긴다 — 손수 세지 않는다.
 *
 * <p><b>배치 분류는 없다</b> — 장르는 draft당 개별 분류다(CLAUDE.md). 재도입하지 마라.
 */
public class GeminiClient implements GenreClassifier {

	private final ChatClient chatClient; // api-key 미설정 시 null(호출 시 예외 — 체인이 2차로 전환)

	public GeminiClient(@Nullable ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	/**
	 * 장르 분류 <b>단일 시도</b> — 설정 확인 → 요청 → 마스터 검증 → 결과.
	 * 재시도·2차 전환은 폴백 체인·아웃박스의 몫이라 여기서 하지 않는다.
	 */
	@Override
	public GenreResult classify(GenreClassification input) {
		if (chatClient == null) {
			throw new GenreClassificationException("Gemini api-key 미설정 — 장르 분류 불가(체인이 2차로 전환)");
		}

		// 요청 — 시스템 프롬프트·구조화 출력 옵션은 ChatClient에 심어져 있고, 매 호출 다른 것은 전시 정보뿐이다.
		ChatResponse response;
		try {
			response = chatClient.prompt()
					.user(input.toPromptText())
					.call()
					.chatResponse();
		} catch (RuntimeException e) {
			throw new GenreClassificationException("Gemini 장르 분류 호출 실패: " + e.getMessage(), e);
		}

		// 마스터 검증: 구조화 출력이 강제해도 방어적으로 한 번 더(마스터 이탈 = 실패 = 예외).
		//   안전필터 등으로 후보가 통째로 없는 응답도 오므로 빈 응답은 null로 흘려 같은 실패로 합류시킨다.
		String genre = Optional.ofNullable(response)
				.map(ChatResponse::getResult)
				.map(Generation::getOutput)
				.map(AssistantMessage::getText)
				.map(String::trim)
				.orElse(null);
		if (!GenreKeyword.contains(genre)) {
			throw new GenreClassificationException("Gemini 장르 응답이 마스터에 없음: " + genre);
		}
		// 계보의 model은 요청 모델이 아니라 응답이 말한 실서빙 모델이다 — 요청 모델은 별칭일 수 있다.
		return GenreResult.ai(genre, GenreProvider.GEMINI, response.getMetadata().getModel());
	}
}
