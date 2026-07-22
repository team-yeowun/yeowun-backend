package modi.backend.ingestion.properties;

import modi.backend.config.AiProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 장르 분류 2차 공급자(OpenAI) 설정. {@code app.exhibition.genre.openai.*} 바인딩(ADR-11).
 *
 * <p>감상문/리마인드용 {@code app.ai.*}({@link AiProperties})와 <b>버킷을 분리</b>한다 — 장르 백필이 한도를 태워도
 * 감상문 경로에 영향을 주지 않게(1차 Gemini가 {@code app.ai.gemini}와 분리된 것과 같은 방침).
 * api-key는 시크릿 → 환경변수 주입. 미설정이면 2차 전환 시도가 실패해 아웃박스 재시도로 넘어간다.
 */
@ConfigurationProperties(prefix = "app.exhibition.genre.openai")
public record GenreOpenAiProperties(String baseUrl, String apiKey, String model, Long timeoutSeconds) {

	public GenreOpenAiProperties {
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "https://api.openai.com/v1";
		}
		if (model == null || model.isBlank()) {
			// 분류(단답) 작업이라 저비용 모델로 충분하다. 바꾸려면 GENRE_OPENAI_MODEL.
			//   같은 전시로 실측했을 때 gpt-4.1-mini보다 싸면서($0.20/$1.25 vs $0.40/$1.60) 더 정확했다 —
			//   허용 목록을 프롬프트에 안 붙인 조건에서도 인상주의 회화전을 맞혔다(mini는 "미디어아트"로 틀림).
			model = "gpt-5.4-nano";
		}
		if (timeoutSeconds == null || timeoutSeconds <= 0) {
			timeoutSeconds = 60L; // 1차(Gemini)와 같은 근거 — 워커 스레드 장기 점유 방지.
		}
	}

	/** api-key가 실제로 채워졌는지(빈 문자열 = 미설정 → 2차 전환 불가). */
	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}
}
