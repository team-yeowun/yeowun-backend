package modi.backend.application.admin;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.support.cache.CacheLookupMetrics;
import modi.backend.support.cache.CacheManager;
import modi.backend.support.cache.CacheType;
import modi.backend.support.cache.MyCache;

/**
 * - 관리자 콘솔의 캐시 현황
 *   - 선언 목록({@link ExhibitionCache#ALL})을 순회해 캐시별 히트율과 적재 상태를 모은다
 *   - 무효화 경로의 건강 상태(구독·발행 실패)도 같은 화면에서 본다
 *
 * - 히트율의 출처가 두 곳인 것이 요점
 *   - 계층별 히트/미스는 창구가 센 값({@link CacheLookupMetrics})
 *   - Redis의 {@code keyspace_hits}를 쓰지 않는 이유는 그 값이 인스턴스 전체 것이라
 *     조회수 누산·AI 임시저장까지 섞여 캐시 히트율이 아니게 되기 때문
 *   - L1 축출·엔트리 수만 Caffeine 자체 통계에서 읽는다
 */
@Service
@RequiredArgsConstructor
public class AdminCacheFacade {

	private final CacheManager cacheManager;
	private final CacheLookupMetrics lookupMetrics;
	private final MeterRegistry meterRegistry;

	public AdminCacheResult.Overview overview() {
		List<AdminCacheResult.CacheStat> caches = ExhibitionCache.ALL.stream().map(this::statOf).toList();

		long l1 = caches.stream().mapToLong(AdminCacheResult.CacheStat::l1Hits).sum();
		long l2 = caches.stream().mapToLong(AdminCacheResult.CacheStat::l2Hits).sum();
		long miss = caches.stream().mapToLong(AdminCacheResult.CacheStat::misses).sum();
		long total = l1 + l2 + miss;

		return new AdminCacheResult.Overview(caches, invalidationHealth(), l1, l2, miss,
				total == 0 ? -1 : (double) (l1 + l2) / total);
	}

	private AdminCacheResult.CacheStat statOf(MyCache cache) {
		CacheLookupMetrics.Counts counts = lookupMetrics.snapshot(cache);
		CacheStats l1 = cacheManager.localStats(cache);

		// 엔트리가 하나뿐인 캐시만 "지금 L2에 올라가 있나"가 의미를 갖는다.
		// 상세는 전시 id마다 키가 달라 대표 키라는 것이 없으므로 확인하지 않는다.
		boolean singleEntry = cache != ExhibitionCache.ExhibitionDetail.INSTANCE;
		boolean loaded = singleEntry && cacheManager.existsInL2(cache, ExhibitionCache.ENTRY_KEY);

		return new AdminCacheResult.CacheStat(
				cache.getName(), cache.getDescription(), cache.getType().name(),
				cache.getTtl().toSeconds(), redisTtlOf(cache),
				counts.l1Hits(), counts.l2Hits(), counts.misses(), counts.hitRate(),
				l1.requestCount() == 0 ? -1 : l1.hitRate(),
				cacheManager.localSize(cache), l1.evictionCount(),
				singleEntry, loaded);
	}

	private static long redisTtlOf(MyCache cache) {
		return cache instanceof MyCache.TwoTierCache twoTier ? twoTier.getRedisTtl().toSeconds()
				: cache.getType() == CacheType.REDIS ? cache.getTtl().toSeconds() : 0L;
	}

	private AdminCacheResult.InvalidationHealth invalidationHealth() {
		return new AdminCacheResult.InvalidationHealth(
				gauge("modi.cache.invalidation.subscribed") > 0,
				counter("modi.cache.invalidation.publish", "success"),
				counter("modi.cache.invalidation.publish", "failure"),
				counter("modi.cache.invalidation.receive", "success"),
				counter("modi.cache.invalidation.receive", "failure"),
				counter("modi.cache.invalidation.resubscribe", null));
	}

	private double counter(String name, String result) {
		Search search = meterRegistry.find(name);
		if (result != null) {
			search = search.tag("result", result);
		}
		return Optional.ofNullable(search.counter()).map(io.micrometer.core.instrument.Counter::count).orElse(0d);
	}

	private double gauge(String name) {
		return Optional.ofNullable(meterRegistry.find(name).gauge())
				.map(io.micrometer.core.instrument.Gauge::value).orElse(0d);
	}
}
