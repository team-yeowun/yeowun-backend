package modi.backend.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.support.cache.CacheLookupMetrics;
import modi.backend.support.cache.CacheManager;

/**
 * - 관리자 캐시 화면이 내보내는 숫자의 의미를 고정한다
 *   - 히트율의 분모가 "창구가 센 조회"인지
 *   - 조회가 없을 때 0%가 아니라 "모름"으로 나가는지
 *
 * - "모름"과 0%의 구분이 이 화면의 핵심이다
 *   - 0%로 찍으면 아무도 안 쓴 캐시가 "전부 미스나는 고장난 캐시"로 보인다
 *   - 그 오해로 멀쩡한 캐시를 걷어내는 판단을 할 수 있다
 */
class AdminCacheFacadeTest {

	private CacheManager cacheManager;
	private CacheLookupMetrics lookupMetrics;
	private MeterRegistry registry;
	private AdminCacheFacade facade;

	@BeforeEach
	void setUp() {
		cacheManager = mock(CacheManager.class);
		registry = new SimpleMeterRegistry();
		lookupMetrics = new CacheLookupMetrics(registry);
		facade = new AdminCacheFacade(cacheManager, lookupMetrics, registry);
		lenient().when(cacheManager.localStats(any())).thenReturn(CacheStats.empty());
		lenient().when(cacheManager.localSize(any())).thenReturn(0L);
		lenient().when(cacheManager.existsInL2(any(), anyString())).thenReturn(false);
	}

	@Test
	@DisplayName("선언한 캐시 전부가 화면에 나온다 — 빠지면 그 캐시는 영영 관찰되지 않는다")
	void overview_선언전체_노출() {
		assertThat(facade.overview().caches())
				.hasSize(ExhibitionCache.ALL.size())
				.extracting(AdminCacheResult.CacheStat::name)
				.contains("HomeBanners", "ExploreLatestP1", "ExhibitionDetail", "HomeNewThisMonth");
	}

	@Test
	@DisplayName("조회가 없으면 히트율은 0%가 아니라 -1(모름)이다")
	void overview_조회없음_모름() {
		AdminCacheResult.Overview o = facade.overview();

		assertThat(o.overallHitRate()).isEqualTo(-1);
		assertThat(o.caches()).allSatisfy(c -> assertThat(c.hitRate()).isEqualTo(-1));
	}

	@Test
	@DisplayName("계층별로 센 값이 그대로 히트율의 분자·분모가 된다")
	void overview_계층별_집계() {
		lookupMetrics.l1Hit(ExhibitionCache.HomeBanners.INSTANCE);
		lookupMetrics.l1Hit(ExhibitionCache.HomeBanners.INSTANCE);
		lookupMetrics.l2Hit(ExhibitionCache.HomeBanners.INSTANCE);
		lookupMetrics.miss(ExhibitionCache.HomeBanners.INSTANCE);

		AdminCacheResult.CacheStat banners = facade.overview().caches().stream()
				.filter(c -> c.name().equals("HomeBanners")).findFirst().orElseThrow();

		assertThat(banners.l1Hits()).isEqualTo(2);
		assertThat(banners.l2Hits()).isEqualTo(1);
		assertThat(banners.misses()).isEqualTo(1);
		assertThat(banners.hitRate()).isEqualTo(0.75);   // (2+1)/4
	}

	@Test
	@DisplayName("전체 히트율은 캐시들을 합산한 값이다")
	void overview_전체합산() {
		lookupMetrics.l1Hit(ExhibitionCache.HomeBanners.INSTANCE);
		lookupMetrics.miss(ExhibitionCache.ExploreLatestP1.INSTANCE);

		AdminCacheResult.Overview o = facade.overview();

		assertThat(o.totalL1Hits()).isEqualTo(1);
		assertThat(o.totalMisses()).isEqualTo(1);
		assertThat(o.overallHitRate()).isEqualTo(0.5);
	}

	@Test
	@DisplayName("상세 캐시는 키가 전시마다 달라 적재 여부를 표시하지 않는다")
	void overview_상세는_단일엔트리아님() {
		AdminCacheResult.CacheStat detail = facade.overview().caches().stream()
				.filter(c -> c.name().equals("ExhibitionDetail")).findFirst().orElseThrow();

		assertThat(detail.singleEntry()).isFalse();
		assertThat(detail.loadedInL2()).isFalse();
	}

	@Test
	@DisplayName("무효화 건강 상태는 지표에서 읽는다 — 구독이 끊기면 화면이 그것을 드러낸다")
	void overview_무효화상태() {
		assertThat(facade.overview().invalidation().subscribed()).isFalse();

		registry.gauge("modi.cache.invalidation.subscribed", 1);
		registry.counter("modi.cache.invalidation.publish", "result", "failure").increment(3);

		AdminCacheResult.InvalidationHealth h = facade.overview().invalidation();
		assertThat(h.subscribed()).isTrue();
		assertThat(h.publishFailure()).isEqualTo(3);
	}
}
