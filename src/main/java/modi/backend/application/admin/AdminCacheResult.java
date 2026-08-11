package modi.backend.application.admin;

import java.util.List;

/**
 * 관리자 캐시 현황 출력 모음. 내부 콘솔이라 이 Result를 응답으로 그대로 내보낸다(기존 admin 경로와 같은 단순화).
 */
public final class AdminCacheResult {

	private AdminCacheResult() {
	}

	/**
	 * - 캐시 한 종의 현황
	 *   - {@code hitRate}는 -1이면 "아직 조회가 없어 모른다"는 뜻(0%와 구분)
	 *   - {@code l1HitRate}는 Caffeine이 자체 집계한 값이고, 나머지는 창구가 센 값
	 *
	 * @param l1Hits  L1에서 끝난 조회
	 * @param l2Hits  L1은 비었고 L2에서 찾은 조회(이때 L1에 되채움이 일어난다)
	 * @param misses  둘 다 없어 loader가 돈 조회
	 * @param l1Size  현재 L1 엔트리 수(추정)
	 * @param l1Evictions Caffeine이 상한·TTL로 밀어낸 수
	 * @param loadedInL2  대표 키가 지금 L2에 올라가 있는가(단일 엔트리 캐시만 의미 있음)
	 */
	public record CacheStat(String name, String description, String type, long ttlSeconds, long redisTtlSeconds,
			long l1Hits, long l2Hits, long misses, double hitRate, double l1HitRate,
			long l1Size, long l1Evictions, boolean singleEntry, boolean loadedInL2) {
	}

	/**
	 * - 무효화 경로의 건강 상태
	 *   - {@code subscribed}가 0이면 이 서버는 방송을 못 받는 중(요청은 200이라 밖에서는 안 보인다)
	 *   - {@code publishFailure}가 0이 아니면 L2 오염이 남아 있을 수 있음 → 수동 워밍 검토
	 */
	public record InvalidationHealth(boolean subscribed, double publishSuccess, double publishFailure,
			double receiveSuccess, double receiveFailure, double resubscribeCount) {
	}

	/** 캐시 탭 한 판. */
	public record Overview(List<CacheStat> caches, InvalidationHealth invalidation,
			long totalL1Hits, long totalL2Hits, long totalMisses, double overallHitRate) {
	}
}
