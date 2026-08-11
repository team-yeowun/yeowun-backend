package modi.backend.support.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;

import modi.backend.infra.cache.RedisPublisher;

@ExtendWith(MockitoExtension.class)
class CacheManagerTest {

	private static final class TestCache extends MyCache.TwoTierCache {
		private static final TestCache INSTANCE = new TestCache();

		private TestCache() {
			super("테스트", Duration.ofMinutes(1), Duration.ofHours(1), String.class);
		}
	}

	@Mock
	private CaffeineCacheManager localCacheManager;
	@Mock
	private RedisCacheManager redisCacheManager;
	@Mock
	private RedisPublisher redisPublisher;
	@Mock
	private CacheInvalidationMetrics invalidationMetrics;
	@Mock
	private CacheLookupMetrics lookupMetrics;
	@Mock
	private Cache localCache;
	@Mock
	private Cache redisCache;

	@InjectMocks
	private CacheManager cacheManager;

	@Test
	@DisplayName("L1이 비면 L2를 보고, 찾으면 L1에 되채운다")
	void get_L2히트_L1되채움() {
		given(localCacheManager.getCache(anyString())).willReturn(localCache);
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);
		given(localCache.get("ALL", String.class)).willReturn(null);
		given(redisCache.get("ALL", String.class)).willReturn("값");

		String result = cacheManager.get(TestCache.INSTANCE, "ALL", String.class);

		assertThat(result).isEqualTo("값");
		verify(localCache).put("ALL", "값");
	}

	@Test
	@DisplayName("캐시가 터져도 예외가 밖으로 나가지 않고 loader로 폴백한다")
	void getOrPut_캐시장애_loader폴백() {
		given(localCacheManager.getCache(anyString())).willReturn(localCache);
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);
		given(localCache.get(any(), any(Class.class))).willThrow(new RuntimeException("Redis down"));
		given(redisCache.get(any(), any(Class.class))).willThrow(new RuntimeException("Redis down"));

		String result = cacheManager.getOrPut(TestCache.INSTANCE, "ALL", String.class, () -> "DB에서 온 값");

		assertThat(result).isEqualTo("DB에서 온 값");
	}

	@Test
	@DisplayName("evict는 L2 삭제·L1 삭제·방송을 항상 함께 한다")
	void evict_세동작_세트() {
		given(localCacheManager.getCache(anyString())).willReturn(localCache);
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);

		cacheManager.evict(TestCache.INSTANCE, "127");

		verify(redisCache).evict("127");
		verify(localCache).evict("127");
		verify(redisPublisher).publish("TestCache:127");
	}
}
