package modi.backend.support.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * - 삭제(무효화) 결과가 지표에 남는지 고정
 *   - 대기열·재시도를 두지 않았으므로 창구가 실패에 대해 하는 일은 "세는 것"뿐
 *   - 그 카운터가 대기열을 뺀 결정의 근거이므로, 조용히 안 오르면 결정 자체가 검증 불가가 됨
 *
 * - 예외 비전파도 함께 봄
 *   - Redis 삭제가 실패해도 관리자 수정 요청은 성공해야 함
 */
@ExtendWith(MockitoExtension.class)
class CacheInvalidationMetricsTest {

	private static final class TestCache extends MyCache.TwoTierCache {
		private static final TestCache INSTANCE = new TestCache();

		private TestCache() {
			super("테스트", Duration.ofMinutes(10), Duration.ofMinutes(30), String.class);
		}
	}

	@Mock
	private CaffeineCacheManager localCacheManager;
	@Mock
	private RedisCacheManager redisCacheManager;
	@Mock
	private Cache localCache;
	@Mock
	private Cache redisCache;

	private MeterRegistry registry;
	private CacheManager cacheManager;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		cacheManager = new CacheManager(localCacheManager, redisCacheManager, new CacheInvalidationMetrics(registry),
				new CacheLookupMetrics(registry));
		lenient().when(localCacheManager.getCache(anyString())).thenReturn(localCache);
		lenient().when(redisCacheManager.getCache(anyString())).thenReturn(redisCache);
	}

	private double evictCount(String result) {
		return registry.counter(CacheInvalidationMetrics.EVICT, "result", result).count();
	}

	@Test
	@DisplayName("Redis 삭제에 성공하면 success로 센다")
	void 삭제성공_계측() {
		cacheManager.evict(TestCache.INSTANCE, "ALL");

		assertThat(evictCount("success")).isEqualTo(1);
		assertThat(evictCount("failure")).isZero();
	}

	@Test
	@DisplayName("Redis가 죽어 삭제에 실패해도 예외는 나가지 않고 failure로 센다 — 자기 L1은 그래도 지운다")
	void 삭제실패_계측() {
		willThrow(new RuntimeException("Redis down")).given(redisCache).evict(anyString());

		assertThatCode(() -> cacheManager.evict(TestCache.INSTANCE, "ALL")).doesNotThrowAnyException();

		assertThat(evictCount("failure")).isEqualTo(1);
		assertThat(evictCount("success")).isZero();
		verify(localCache).evict("ALL");
	}

	@Test
	@DisplayName("방송 지표(publish·receive)는 더 이상 생기지 않는다")
	void 방송지표_없음() {
		cacheManager.evict(TestCache.INSTANCE, "ALL");

		assertThat(registry.find("modi.cache.invalidation.publish").meters()).isEmpty();
		assertThat(registry.find("modi.cache.invalidation.receive").meters()).isEmpty();
	}
}
