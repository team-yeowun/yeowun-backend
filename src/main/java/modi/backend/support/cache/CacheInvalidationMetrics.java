package modi.backend.support.cache;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * - 캐시 삭제(무효화)의 감지 수단
 *   - 캐시 실패는 예외를 삼켜 처리하므로 로그 말고는 흔적이 남지 않음
 *   - 세어 두지 않으면 삭제가 조용히 실패한 채 옛 값이 Redis TTL(30분)까지 서빙됨
 *
 * - 삭제 대기열·재시도를 두지 않기로 한 결정의 근거이기도 함
 *   - "Redis 삭제 실패는 드물고, 실패해도 TTL이 정리한다"는 가정 위에서 대기열을 뺐음
 *   - 이 실패 카운터가 0으로 유지되면 그 판단이 옳았던 것이고, 튀면 그때 대기열을 넣으면 됨
 *
 * - 성공도 함께 셈
 *   - 실패 수만 보면 "삭제가 아예 없었던 것"과 "다 성공한 것"을 구분할 수 없음
 *
 * - 지표 이름은 {@code modi.} 접두사를 따름(기존 AI 호출 계측과 같은 규칙)
 */
@Component
public class CacheInvalidationMetrics {

	static final String EVICT = "modi.cache.invalidation.evict";

	private final MeterRegistry meterRegistry;

	public CacheInvalidationMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void evicted() {
		meterRegistry.counter(EVICT, "result", "success").increment();
	}

	/** 경보 대상 — 그 키는 Redis TTL이 끝날 때까지 옛 값일 수 있다. */
	public void evictFailed() {
		meterRegistry.counter(EVICT, "result", "failure").increment();
	}
}
