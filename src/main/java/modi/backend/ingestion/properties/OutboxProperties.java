package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import modi.backend.ingestion.domain.outbox.RetryPolicy;

/**
 * 전시 아웃박스({@code exhibition_outbox}) 설정. {@code app.exhibition.outbox.*} 바인딩.
 *
 * <p><b>재시도 정책은 하나다(설계 D5)</b> — 총 시도 {@code maxAttempts}(기본 3 = 최초 1 + 재시도 2), 소진 시
 * FAILED_PERMANENT(이후는 관리자 수동 재시도만). 구 장르 무기한 특례(genreRetryPolicy)는 폐지됐다.
 * 폴링 주기 기본 12시간 — 즉시성은 커밋 직후 적재 알림이 담당하고, 폴링은 재시도 도래분·유실분 줍기 전용이다.
 *
 * @param maxAttempts        총 시도 상한(초과 시 FAILED_PERMANENT로 승격). 기본 3.
 * @param baseBackoffSeconds 지수 백오프의 기준 간격(첫 재시도까지의 초).
 * @param maxBackoffSeconds  백오프 상한(간격이 무한정 벌어지지 않게).
 * @param batchSize          한 번의 소비에서 집는 메시지 수 상한(폴링 폭주 방지).
 * @param pollIntervalMs     릴레이 폴링 주기(ms) — durable 엔진의 심박. 기본 12시간.
 * @param purgeRetentionDays SUCCEEDED 보존 기간(일) — 이보다 오래된 성공 행만 주간 정리 대상. 기본 7일.
 * @param purgeBatchSize     정리 1배치 삭제 상한 — 소량 배치 원칙(100만 건 실험 §9). 기본 500.
 */
@ConfigurationProperties(prefix = "app.exhibition.outbox")
public record OutboxProperties(Integer maxAttempts, Long baseBackoffSeconds, Long maxBackoffSeconds,
		Integer batchSize, Long pollIntervalMs, Integer purgeRetentionDays, Integer purgeBatchSize) {

	public OutboxProperties {
		if (maxAttempts == null || maxAttempts < 1) {
			maxAttempts = 3;
		}
		if (baseBackoffSeconds == null || baseBackoffSeconds < 1) {
			baseBackoffSeconds = 60L;
		}
		if (maxBackoffSeconds == null || maxBackoffSeconds < baseBackoffSeconds) {
			maxBackoffSeconds = 3600L;
		}
		if (batchSize == null || batchSize < 1) {
			batchSize = 50;
		}
		if (pollIntervalMs == null || pollIntervalMs < 1000) {
			pollIntervalMs = 43_200_000L;
		}
		if (purgeRetentionDays == null || purgeRetentionDays < 1) {
			purgeRetentionDays = 7;
		}
		if (purgeBatchSize == null || purgeBatchSize < 1) {
			purgeBatchSize = 500;
		}
	}

	/** 설정값으로 도메인 백오프 정책을 만든다(도메인은 설정을 모르므로 여기서 조립해 넘긴다). */
	public RetryPolicy retryPolicy() {
		return new RetryPolicy(maxAttempts, baseBackoffSeconds, maxBackoffSeconds);
	}
}
