package modi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI(LLM) 설정. {@code app.ai.*} 바인딩. provider는 교체 가능하도록 문자열로 둔다(claude | gemini).
 * api-key는 시크릿 → 환경변수 주입. provider와 짝지어 지정한다(교차 오염 방지):
 *   claude=ANTHROPIC_API_KEY, gemini=AI_API_KEY(=Gemini 키). 미설정이면 어댑터가 비활성(AI_DISABLED)으로 동작.
 * model 미지정 시 provider에 맞는 기본 모델을 쓴다(claude → claude-opus-4-8, gemini → gemini-2.5-flash-lite).
 * timeoutSeconds: 외부 LLM 호출 타임아웃(워커 스레드 장기 점유 방지).
 * rateLimitSeconds: 사용자당 AI 호출 최소 간격(반복 클릭에 의한 유료 호출 폭주 방지).
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(String provider, String model, String apiKey, Long maxTokens,
		Long timeoutSeconds, Long rateLimitSeconds, Integer maxRetries, Long maxRetryDelaySeconds) {

	// 로컬 기본값을 Sonnet 5로 — 감상문/질문/요약은 경량 작업이라 Opus는 latency 과지출(로컬 compose ~6s → ~3-4s).
	//   프로덕션도 AI_MODEL=claude-sonnet-5로 동일(deploy.yml). 더 빠르게 하려면 AI_MODEL=claude-haiku-4-5-20251001.
	private static final String DEFAULT_CLAUDE_MODEL = "claude-sonnet-5";
	// 무료 한도가 flash보다 훨씬 큰 flash-lite를 기본값으로 — 감상문 질문/다듬기는 가벼운 작업이라 lite로 품질 충분.
	//   장르 분류(app.ai.gemini, 기본 flash)와 모델을 다르게 유지해 무료 한도 버킷을 분리한다(Gemini 한도는 모델별).
	private static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite";

	public AiProperties {
		if (provider == null || provider.isBlank()) {
			provider = "claude";
		}
		if (model == null || model.isBlank()) {
			model = "gemini".equalsIgnoreCase(provider) ? DEFAULT_GEMINI_MODEL : DEFAULT_CLAUDE_MODEL;
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
