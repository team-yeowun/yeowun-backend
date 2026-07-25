package modi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI(LLM) 설정. {@code app.ai.*} 바인딩. 감상문·리마인드 전용이며 공급자는 <b>Claude 하나</b>다
 * (Gemini 어댑터는 제거됐다 — 쓰이지 않는 두 번째 경로를 유지할 값이 없었다. 전시 장르 분류는
 * {@code app.exhibition.genre.*}로 완전히 분리된 별개 경로다).
 * api-key는 시크릿 → 환경변수 주입(ANTHROPIC_API_KEY). 미설정이면 어댑터가 비활성(AI_DISABLED)으로 동작한다.
 * timeoutSeconds: 외부 LLM 호출 타임아웃(워커 스레드 장기 점유 방지).
 * rateLimitSeconds: 사용자당 AI 호출 최소 간격(반복 클릭에 의한 유료 호출 폭주 방지).
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(String model, String apiKey, Long maxTokens,
		Long timeoutSeconds, Long rateLimitSeconds, Integer maxRetries, Long maxRetryDelaySeconds) {

	// 로컬 기본값을 Sonnet 5로 — 감상문/질문/요약은 경량 작업이라 Opus는 latency 과지출(로컬 compose ~6s → ~3-4s).
	//   프로덕션도 AI_MODEL=claude-sonnet-5로 동일(deploy.yml). 더 빠르게 하려면 AI_MODEL=claude-haiku-4-5-20251001.
	private static final String DEFAULT_CLAUDE_MODEL = "claude-sonnet-5";
	public AiProperties {
		if (model == null || model.isBlank()) {
			model = DEFAULT_CLAUDE_MODEL;
		}
		if (maxTokens == null || maxTokens <= 0) {
			// ≤300자 감상문/요약·질문 3개엔 충분하면서 폭주 생성을 바운드(M-4 — 최악 생성시간 상한). 필요 시 AI_MAX_TOKENS로 상향.
			maxTokens = 768L;
		}
		if (timeoutSeconds == null || timeoutSeconds <= 0) {
			timeoutSeconds = 30L;
		}
		if (rateLimitSeconds == null || rateLimitSeconds < 0) {
			rateLimitSeconds = 3L;
		}
		if (maxRetries == null || maxRetries < 0) {
			maxRetries = 2;
		}
		if (maxRetryDelaySeconds == null || maxRetryDelaySeconds < 0) {
			maxRetryDelaySeconds = 4L;
		}
	}

	/** api-key가 실제로 채워졌는지(빈 문자열 = 미설정). */
	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}
}
