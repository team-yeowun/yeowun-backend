package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import modi.backend.ingestion.domain.outbox.RetryPolicy;

/**
 * 전시 아웃박스({@code exhibition_outbox}) 설정. {@code app.exhibition.outbox.*} 바인딩.
 *
 * <p>durable 재시도 백오프({@link #retryPolicy()})와 릴레이의 선별 배치 크기·폴링 주기, 그리고 이벤트 구동
 * 영업시간 재검증의 최소 간격(설계 §4-1 가드)을 담는다.
 *
 * @param maxAttempts                 재시도 가능한 실패를 몇 번까지 다시 시도할지(초과 시 FAILED_PERMANENT로 승격).
 * @param baseBackoffSeconds          지수 백오프의 기준 간격(첫 재시도까지의 초).
 * @param maxBackoffSeconds           백오프 상한(간격이 무한정 벌어지지 않게).
 * @param batchSize                   한 번의 드레인에서 집는 메시지 수 상한(폴링 폭주 방지).
 * @param pollIntervalMs              릴레이 폴링 주기(ms) — durable 엔진의 심박.
 * @param hoursRefreshMinIntervalDays 영업시간 재검증 최소 간격(일). 이 안에 조회된 장소는 REFRESH enqueue를 건너뛴다.
 */
@ConfigurationProperties(prefix = "app.exhibition.outbox")
public record OutboxProperties(Integer maxAttempts, Long baseBackoffSeconds, Long maxBackoffSeconds,
		Integer batchSize, Long pollIntervalMs, Integer hoursRefreshMinIntervalDays) {

	public OutboxProperties {
		if (maxAttempts == null || maxAttempts < 1) {
			maxAttempts = 5;
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
			pollIntervalMs = 60000L;
		}
		if (hoursRefreshMinIntervalDays == null || hoursRefreshMinIntervalDays < 0) {
			hoursRefreshMinIntervalDays = 30;
		}
	}

	/** 설정값으로 도메인 백오프 정책을 만든다(도메인은 설정을 모르므로 여기서 조립해 넘긴다). */
	public RetryPolicy retryPolicy() {
		return new RetryPolicy(maxAttempts, baseBackoffSeconds, maxBackoffSeconds);
	}

	/**
	 * 장르 스텝(DETAIL_FETCHED 소비 = AI 분류) 전용 — <b>시도 소진 없는</b> 백오프 정책(ADR-11). AI 장애는 데이터 문제가 아니라
	 * 인프라 문제라 재시도해도 손해가 없고, 소진 승격(FAILED_PERMANENT)되면 draft가 영구히 승격 불가로 굳는다.
	 * "모든 AI 동시 장애 시 draft 승격 대기(감수)"가 사용자 확정 — 캡된 백오프(상한 {@link #maxBackoffSeconds})로
	 * 회복까지 무기한 재시도한다.
	 */
	public RetryPolicy genreRetryPolicy() {
		return new RetryPolicy(Integer.MAX_VALUE, baseBackoffSeconds, maxBackoffSeconds);
	}
}
