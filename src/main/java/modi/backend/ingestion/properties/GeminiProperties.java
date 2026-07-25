package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini(무료 한도) 장르 분류용 설정. {@code app.ai.gemini.*} 바인딩.
 * api-key는 시크릿 → 환경변수(GEMINI_API_KEY, GitHub Actions secret) 주입. 미설정이면 분류 실패 예외를 던져
 * 체인이 2차로 전환한다. model 기본은 무료 한도에서 동작 확인된 {@code gemini-2.5-flash}.
 * <p>
 * 재시도 설정({@code max-retries}·{@code max-retry-delay-seconds})은 없앴다 — 호출 내 재시도를 걷어냈고,
 * 429를 포함한 실패는 아웃박스의 durable 재시도가 잇는다(ADR-10).
 */
@ConfigurationProperties(prefix = "app.ai.gemini")
public record GeminiProperties(String baseUrl, String apiKey, String model, Long timeoutSeconds) {

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
	}

	/** api-key가 실제로 채워졌는지(빈 문자열 = 미설정 → 호출 없이 분류 실패, 체인이 2차로 전환). */
	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}
}
