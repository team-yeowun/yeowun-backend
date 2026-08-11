package modi.backend.support.cache;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * - 무효화 방송의 감지 수단
 *   - 캐시 실패는 예외를 삼켜 처리하므로 로그 말고는 흔적이 남지 않음
 *   - 세어 두지 않으면 조용히 실패한 채 옛 값이 계속 서빙됨
 *
 * - 대기열을 만들지 않기로 한 결정의 근거이기도 함
 *   - "발행 실패가 드물다"는 가정 위에서 대기열을 뺐음
 *   - 이 카운터가 0으로 유지되면 그 판단이 옳았던 것이고, 튀면 그때 대기열을 넣으면 됨
 *   - 즉 지표를 빼면 그 결정이 근거를 잃음
 *
 * - 발행 성공도 함께 셈
 *   - 실패 수만 보면 "발행이 아예 없었던 것"과 "다 성공한 것"을 구분할 수 없음
 *   - Pub/Sub은 발신자도 자기 메시지를 받으므로, 정상이면 수신 합 = 발행 합 × 서버 수
 *   - 이 비율이 떨어지면 어느 서버의 구독이 끊긴 것(틈 ②)
 *   - 구독 여부 게이지는 컨테이너가 소유함(CacheConfig) — 콜백 플래그보다 실제 상태에 가깝기 때문
 *
 * - 지표 이름은 {@code modi.} 접두사를 따름(기존 AI 호출 계측과 같은 규칙)
 */
@Component
public class CacheInvalidationMetrics {

	private static final String PUBLISH = "modi.cache.invalidation.publish";
	private static final String RECEIVE = "modi.cache.invalidation.receive";
	private static final String RESUBSCRIBE = "modi.cache.invalidation.resubscribe";

	private final MeterRegistry meterRegistry;

	public CacheInvalidationMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void published() {
		meterRegistry.counter(PUBLISH, "result", "success").increment();
	}

	/** 틈 ① — 경보 대상. */
	public void publishFailed() {
		meterRegistry.counter(PUBLISH, "result", "failure").increment();
	}

	public void received() {
		meterRegistry.counter(RECEIVE, "result", "success").increment();
	}

	/** 틈 ③ — 포맷이 바뀌었거나 수신 코드에 버그가 있다는 신호. */
	public void receiveFailed() {
		meterRegistry.counter(RECEIVE, "result", "failure").increment();
	}

	/**
	 * - 구독이 확인된 횟수(최초 구독 포함)
	 *   - 이 값이 계속 오르면 구독이 붙었다 끊겼다 하는 중(틈 ②)
	 *   - 구독 중인지 여부 자체는 컨테이너가 게이지로 내보냄({@code modi.cache.invalidation.subscribed})
	 */
	public void resubscribed() {
		meterRegistry.counter(RESUBSCRIBE).increment();
	}
}
