package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini(무료 한도) 장르 분류용 설정. {@code app.ai.gemini.*} 바인딩.
 * api-key는 시크릿 → 환경변수(GEMINI_API_KEY, GitHub Actions secret) 주입. 미설정이면 AI 분류기가 랜덤으로 폴백한다.
 * model 기본은 무료 한도에서 동작 확인된 {@code gemini-2.5-flash}. 429(한도 초과)는 max-retries·max-retry-delay-seconds로 제어한다.
 */
@ConfigurationProperties(prefix = "app.ai.gemini")
public record GeminiProperties(String baseUrl, String apiKey, String model,
		Long timeoutSeconds, Integer maxRetries, Long maxRetryDelaySeconds) {

	public GeminiProperties {
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "https://generativelanguage.googleapis.com";
		}
		if (model == null || model.isBlank()) {
			model = "gemini-2.5-flash";
		}
		if (timeoutSeconds == null || timeoutSeconds <= 0) {
			// 배치(초기화 백필)는 여러 전시를 한 응답으로 받아 단건보다 오래 걸린다(모델 사고 토큰 포함).
			// 20s는 20건 배치가 EC2에서 ReadTimeout → 전량 랜덤 폴백됐다. 여유 있게 60s(단일 호출·best-effort).
			timeoutSeconds = 60L;
		}
		if (maxRetries == null || maxRetries < 0) {
			maxRetries = 1;
		}
		if (maxRetryDelaySeconds == null || maxRetryDelaySeconds < 0) {
			maxRetryDelaySeconds = 2L;
		}
	}

	/** api-key가 실제로 채워졌는지(빈 문자열 = 미설정 → 랜덤 폴백). */
	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}
}
