package modi.backend.ingestionv2.common.queue;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 미처리 항목의 회수 자격 판정 규칙.
 *
 * <ul>
 *   <li>판정 입력은 둘 - 마지막 전달 이후 경과 시간과 지금까지의 전달 횟수</li>
 *   <li>지수는 {@code base x 2^(전달횟수-1)} 이고 {@code max} 에서 멈춘다 - 낫지 않는 실패가 같은 간격으로
 *       영원히 되돌아오는 것을 막는다</li>
 *   <li>지터는 계산된 지연 안에서 고르되 base 아래로는 내려가지 않음 - XCLAIM 가드가 base 를
 *       최솟값으로 받아, base 미만의 임계치를 주면 회수를 요청하고도 조용히 못 받는 항목이 생김(N-06)</li>
 *   <li>영속 상태가 아니다 - 다음 시도 시각을 어디에도 적지 않는다. 대기열의 경과 시간이 이미 그 정보를 갖고 있고,
 *       상태를 하나 더 두면 그 상태와 대기열이 어긋날 자리가 생긴다</li>
 *   <li>소비 경로 전용 - 발행 경로는 다음 틱 폴링이 이미 고정 간격 복구 경로다</li>
 * </ul>
 */
public record ReclaimBackoffPolicy(ReclaimBackoff backoff, ReclaimJitter jitter, Duration base, Duration max) {

	/** 지수 계산의 자리 넘침 방지 - 이 이상은 어차피 상한에 걸린다. */
	private static final int MAX_SHIFT = 40;

	public ReclaimBackoffPolicy {
		if (backoff == null) {
			backoff = ReclaimBackoff.NONE;
		}
		if (jitter == null) {
			jitter = ReclaimJitter.NONE;
		}
		if (max == null || max.compareTo(base) < 0) {
			max = base;
		}
	}

	/** 회수 대상인지 - 경과 시간이 이 전달 횟수에 매겨진 임계치를 넘었는가. */
	public boolean eligible(Duration elapsedSinceLastDelivery, long deliveryCount) {
		return elapsedSinceLastDelivery.compareTo(thresholdFor(deliveryCount)) >= 0;
	}

	/** 이 전달 횟수에 매겨진 대기 임계치. 지터가 있으면 호출마다 값이 달라진다. */
	public Duration thresholdFor(long deliveryCount) {
		Duration delay = backoff == ReclaimBackoff.EXPONENTIAL ? exponential(deliveryCount) : base;
		return jitter == ReclaimJitter.FULL ? jittered(delay) : delay;
	}

	private Duration exponential(long deliveryCount) {
		long shift = Math.min(Math.max(deliveryCount - 1, 0), MAX_SHIFT);
		long millis = base.toMillis() << shift;
		return millis <= 0 || millis > max.toMillis() ? max : Duration.ofMillis(millis);
	}

	/** base 를 바닥으로 둔 FULL 지터. 바닥이 없으면 회수 요청과 실제 회수가 어긋난다. */
	private Duration jittered(Duration delay) {
		long baseMillis = base.toMillis();
		long delayMillis = delay.toMillis();
		if (delayMillis <= baseMillis) {
			return base;
		}
		long picked = ThreadLocalRandom.current().nextLong(delayMillis + 1);
		return Duration.ofMillis(Math.max(baseMillis, picked));
	}
}
