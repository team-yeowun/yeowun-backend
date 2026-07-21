package modi.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI '질문으로 작성' 임시저장(draft) 캐시 설정. {@code app.ai.draft.*} 바인딩.
 * ttl: 진행 중 draft(질문+답변+초안)의 Redis 만료 시간 — 작성 세션 유지엔 충분하고, 방치 draft는 자동 정리된다.
 * (AI 모델·키 등은 {@link AiProperties}. draft는 성격이 달라 별도 프로퍼티로 분리 — AiProperties 생성자 시그니처 불변 유지.)
 */
@ConfigurationProperties(prefix = "app.ai.draft")
public record AiDraftProperties(Duration ttl) {

	private static final Duration DEFAULT_TTL = Duration.ofHours(1);

	public AiDraftProperties {
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			ttl = DEFAULT_TTL;
		}
	}
}
