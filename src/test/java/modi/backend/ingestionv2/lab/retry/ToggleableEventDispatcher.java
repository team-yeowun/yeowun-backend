package modi.backend.ingestionv2.lab.retry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.queue.EventDispatcher;
import modi.backend.support.error.CoreException;

/**
 * 발행 장애를 켜고 끄는 lab 어댑터 - step-05 시나리오 A(O-01)의 장치.
 *
 * <ul>
 *   <li>기본은 실물 위임 - 장애를 켠 구간에서만 예외를 던진다</li>
 *   <li>대기열 자체를 죽이지 않고 어댑터만 바꾼다 - 컨테이너를 공유하는 다른 측정을 건드리지 않기 위함</li>
 *   <li>프로덕션에 스위치를 두지 않는다 - 발행을 끄는 플래그가 {@code src/main} 에 있으면 그 자체가 사고 통로다</li>
 * </ul>
 */
final class ToggleableEventDispatcher implements EventDispatcher {

	private final EventDispatcher delegate;
	private final AtomicBoolean failing = new AtomicBoolean();
	private final AtomicInteger attempts = new AtomicInteger();

	ToggleableEventDispatcher(EventDispatcher delegate) {
		this.delegate = delegate;
	}

	@Override
	public void dispatch(OutboxPayload payload) {
		attempts.incrementAndGet();
		if (failing.get()) {
			throw new CoreException(IngestionErrorCode.STREAM_PUBLISH_FAILED,
					"lab 발행 장애 주입 aggregateId=" + payload.aggregateId());
		}
		delegate.dispatch(payload);
	}

	void failing(boolean value) {
		failing.set(value);
	}

	int attempts() {
		return attempts.get();
	}

	void reset() {
		attempts.set(0);
		failing.set(false);
	}
}
