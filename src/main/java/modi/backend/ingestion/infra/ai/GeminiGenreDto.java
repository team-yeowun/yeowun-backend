package modi.backend.ingestion.infra.ai;

import java.util.Optional;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * Gemini 장르 분류 요청/응답 바인딩(외곽 1클래스 + 중첩 record — 컨벤션).
 * <p>
 * 구글({@code GooglePlaceDto})·문화포털과 같은 구조다: <b>요청 record는 도메인 요청을 벤더 어휘로 옮기고,
 * 응답 record는 자기를 도메인 값으로 표현한다</b>({@link GenreAnswer#toGenreResult} —
 * {@code GooglePlaceResponse.toPlaceHours()}와 같은 자리).
 * <p>
 * <b>설정값은 여기 없다.</b> 모델·구조화 출력 규격({@code text/x.enum}·스키마) 같은 값은 {@code GeminiClient}가
 * {@code ChatClient} 기본 옵션으로 심고(연결은 {@code AiModelConfig}), 무엇을 시키고 무엇을 허용할지는 코어가
 * {@link GenreClassificationRequest}로 내려보낸다. 이 클래스는 <b>값을 옮기고 응답을 파싱하는 일만</b> 한다.
 */
public final class GeminiGenreDto {

	private GeminiGenreDto() {
	}

	// ----- 요청 -----

	/**
	 * 도메인 요청을 Gemini 메시지 슬롯(system/user)에 옮긴다. {@code GooglePlaceDto.SearchTextRequest}와 같은 자리이고,
	 * <b>옮기기만 한다</b> — 모델·구조화 출력 같은 설정은 {@code GeminiClient}가 {@code ChatClient}에 심어 둔다.
	 */
	public record GenreRequest(GenreClassificationRequest request) {

		/**
		 * 시스템 지시 — 코어가 정한 지시에 <b>허용 목록을 덧붙인다</b>. 지시문이 "아래 장르 목록 중"이라고 말하는데
		 * 목록이 구조화 출력 스키마에만 있으면 모델은 그 목록을 <b>지시로는 보지 못한다</b> — 실제로 목록 없이
		 * 보냈을 때 인상주의 회화전을 "미디어아트"로 분류하는 것을 실호출로 확인했다(목록을 붙이자 "회화·드로잉").
		 * 스키마는 값을 <b>강제</b>하고, 이 목록은 <b>고르는 근거</b>가 된다 — 둘 다 필요하다.
		 */
		public String systemMessage() {
			return request.instruction() + "%n장르 목록: %s".formatted(String.join(", ", request.allowedGenres()));
		}

		/** 사용자 콘텐츠 — 분류 대상 전시 요약. */
		public String userMessage() {
			return request.subject();
		}
	}

	// ----- 응답 -----

	/**
	 * 분류 응답 한 건 — 고른 장르와 <b>실서빙 모델</b>. 계보에 남길 모델은 요청 모델이 아니라 응답이 말한 값이다
	 * (요청 모델은 별칭일 수 있다).
	 */
	public record GenreAnswer(String genre, String servingModel) {

		/**
		 * Spring AI 응답에서 값을 꺼낸다. 안전필터 등으로 <b>후보가 통째로 없는 응답</b>도 오므로 빈 응답은
		 * {@code genre=null}로 흘려 {@link #toGenreResult}의 허용 검증에서 같은 실패로 합류시킨다.
		 */
		public static GenreAnswer from(ChatResponse response) {
			String genre = Optional.ofNullable(response)
					.map(ChatResponse::getResult)
					.map(Generation::getOutput)
					.map(AssistantMessage::getText)
					.map(String::trim)
					.orElse(null);
			String servingModel = Optional.ofNullable(response)
					.map(ChatResponse::getMetadata)
					.map(metadata -> metadata.getModel())
					.orElse(null);
			return new GenreAnswer(genre, servingModel);
		}

		/**
		 * 자기를 도메인 값으로 표현한다. 구조화 출력이 값을 강제해도 <b>요청이 들고 온 허용 집합</b>으로 한 번 더
		 * 확인하고, 벗어났으면 폴백값이 아니라 예외다(ADR-11 계약 반전).
		 */
		public GenreResult toGenreResult(GenreClassificationRequest request) {
			if (!request.accepts(genre)) {
				throw new GenreClassificationException("Gemini 장르 응답이 허용 집합에 없음: " + genre);
			}
			return GenreResult.ai(genre, GenreProvider.GEMINI, servingModel);
		}
	}
}
