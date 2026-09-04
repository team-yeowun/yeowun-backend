package modi.backend.ingestionv2.common.outbox;

/**
 * 미발행 행을 두 인스턴스가 나눠 집는 방식.
 *
 * <ul>
 *   <li>운영 기본은 {@link #SKIP_LOCKED} - 상태의 권위자인 MySQL이 행 소유권도 함께 판정한다</li>
 *   <li>{@link #NONE}·{@link #PESSIMISTIC}·{@link #REDIS_MARKER}는 부하 실험 비교용 -
 *       같은 무대에서 락 방식만 바꿔 재기 위해 남겨 둔다</li>
 *   <li>네 값의 조회 조건과 정렬은 전부 같다 - 다른 것은 잠금 절 하나뿐이라야 변형 간 비교가 성립한다</li>
 * </ul>
 */
public enum OutboxClaimStrategy {

	/** 잠금 없이 읽고 그대로 발행한다. 두 인스턴스가 같은 행을 집는다(부하 실험 비교용). */
	NONE,

	/** {@code FOR UPDATE} - 뒤에 온 인스턴스는 앞선 인스턴스의 잠금이 풀릴 때까지 기다린다(부하 실험 비교용). */
	PESSIMISTIC,

	/** {@code FOR UPDATE SKIP LOCKED} - 잠긴 행을 기다리지 않고 건너뛰며 동시 발송기에 서로 다른 행을 배분한다. */
	SKIP_LOCKED,

	/** 잠금 없이 읽고 행마다 Redis 마커를 잡아 판정한다(부하 실험 비교용). */
	REDIS_MARKER;

	/** 잠금 절 없는 조회를 쓰는 전략인가. */
	public boolean readsWithoutLock() {
		return this == NONE || this == REDIS_MARKER;
	}

	/** 행마다 Redis 마커로 판정하는 전략인가. */
	public boolean usesMarker() {
		return this == REDIS_MARKER;
	}
}
