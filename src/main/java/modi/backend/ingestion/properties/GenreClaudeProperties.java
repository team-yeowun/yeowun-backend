package modi.backend.ingestion.properties;

import modi.backend.config.AiProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 장르 분류 2차 공급자(Claude) 설정. {@code app.exhibition.genre.claude.*} 바인딩(ADR-11).
 *
 * <p>감상문/리마인드용 {@code app.ai.*}({@link AiProperties})와 <b>버킷을 분리</b>한다 — 장르 백필이 무료/저가
 * 한도를 태워도 감상문 경로의 한도에 영향을 주지 않게(장르용 Gemini가 {@code app.ai.gemini}와 분리된 것과 같은 방침).
 * api-key는 시크릿 → 환경변수(GENRE_CLAUDE_API_KEY) 주입. 미설정이면 2차 전환 시도가 실패해 아웃박스 재시도로 넘어간다.
 * model 기본은 분류(단답) 작업에 맞는 저비용 {@code claude-haiku-4-5-20251001}.
 */
@ConfigurationProperties(prefix = "app.exhibition.genre.claude")
public record GenreClaudeProperties(String apiKey, String model, Integer maxTokens, Long timeoutSeconds) {

	public GenreClaudeProperties {
		if (model == null || model.isBlank()) {
			model = "claude-haiku-4-5-20251001";
		}
		if (maxTokens == null || maxTokens <= 0) {
			// 배치(20건) JSON 배열 응답까지 여유 — 키워드 10여 토큰 × 20 + JSON 골격.
			maxTokens = 1024;
		}
		if (timeoutSeconds == null || timeoutSeconds <= 0) {
			timeoutSeconds = 60L; // Gemini 장르 배치와 같은 근거(20건 배치가 짧은 타임아웃에 잘림).
		}
	}

	/** api-key가 실제로 채워졌는지(빈 문자열 = 미설정 → 2차 전환 불가). */
	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}
}
