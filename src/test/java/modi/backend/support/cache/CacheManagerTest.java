package modi.backend.support.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * - 창구가 선언 타입대로 계층을 고르는지 고정
 *   - TWO_TIER(목록): L1 → L2 → loader
 *   - REDIS(상세): Redis → loader, 로컬 캐시는 한 번도 건드리지 않음
 *
 * - 삭제는 Redis와 이 서버의 L1뿐이고 방송이 없음
 * - Redis가 터져도 응답은 loader(DB)로 나감
 */
@ExtendWith(MockitoExtension.class)
class CacheManagerTest {

	private static final class TwoTier extends MyCache.TwoTierCache {
		private static final TwoTier INSTANCE = new TwoTier();

		private TwoTier() {
			super("목록 테스트", Duration.ofMinutes(10), Duration.ofMinutes(30), String.class);
		}
	}

	private static final class RedisOnly extends MyCache.RedisCache {
		private static final RedisOnly INSTANCE = new RedisOnly();

		private RedisOnly() {
			super("상세 테스트", Duration.ofMinutes(30), String.class);
		}
	}

	@Mock
	private CaffeineCacheManager localCacheManager;
	@Mock
	private RedisCacheManager redisCacheManager;
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
	@DisplayName("목록: L1이 비면 L2를 보고, 찾으면 L1에 되채운다")
	void get_L2히트_L1되채움() {
		given(localCacheManager.getCache(anyString())).willReturn(localCache);
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);
		given(localCache.get("ALL", String.class)).willReturn(null);
		given(redisCache.get("ALL", String.class)).willReturn("값");

		String result = cacheManager.get(TwoTier.INSTANCE, "ALL", String.class);

		assertThat(result).isEqualTo("값");
		verify(localCache).put("ALL", "값");
		verify(lookupMetrics).l2Hit(TwoTier.INSTANCE);
	}

	@Test
	@DisplayName("목록: 캐시가 터져도 예외가 밖으로 나가지 않고 loader로 폴백한다")
	void getOrPut_캐시장애_loader폴백() {
		given(localCacheManager.getCache(anyString())).willReturn(localCache);
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);
		given(localCache.get(any(), any(Class.class))).willThrow(new RuntimeException("L1 down"));
		given(redisCache.get(any(), any(Class.class))).willThrow(new RuntimeException("Redis down"));

		String result = cacheManager.getOrPut(TwoTier.INSTANCE, "ALL", String.class, () -> "DB에서 온 값");

		assertThat(result).isEqualTo("DB에서 온 값");
	}

	@Test
	@DisplayName("상세: Redis에서 찾으면 그대로 돌려주고 로컬 캐시는 보지 않는다")
	void get_Redis전용_히트() {
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);
		given(redisCache.get("42", String.class)).willReturn("상세");

		String result = cacheManager.get(RedisOnly.INSTANCE, "42", String.class);

		assertThat(result).isEqualTo("상세");
		verify(lookupMetrics).l2Hit(RedisOnly.INSTANCE);
		verifyNoInteractions(localCacheManager);
	}

	@Test
	@DisplayName("상세: 미스면 loader 결과를 Redis에만 적재한다(로컬 복사본 없음)")
	void getOrPut_Redis전용_미스_Redis에만적재() {
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);
		given(redisCache.get("42", String.class)).willReturn(null);

		String result = cacheManager.getOrPut(RedisOnly.INSTANCE, "42", String.class, () -> "DB 상세");

		assertThat(result).isEqualTo("DB 상세");
		verify(redisCache).put("42", "DB 상세");
		verify(lookupMetrics).miss(RedisOnly.INSTANCE);
		verifyNoInteractions(localCacheManager);
	}

	@Test
	@DisplayName("상세: Redis가 죽어도 응답은 loader(DB)로 나간다")
	void getOrPut_Redis장애_DB응답() {
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);
		given(redisCache.get(any(), any(Class.class))).willThrow(new RuntimeException("Redis down"));
		org.mockito.BDDMockito.willThrow(new RuntimeException("Redis down")).given(redisCache).put(any(), any());

		String result = cacheManager.getOrPut(RedisOnly.INSTANCE, "42", String.class, () -> "DB 상세");

		assertThat(result).isEqualTo("DB 상세");
	}

	@Test
	@DisplayName("목록 evict는 Redis와 이 서버의 L1을 지운다(방송 없음)")
	void evict_목록_Redis와자기L1() {
		given(localCacheManager.getCache(anyString())).willReturn(localCache);
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);

		cacheManager.evict(TwoTier.INSTANCE, "ALL");

		verify(redisCache).evict("ALL");
		verify(localCache).evict("ALL");
		verify(invalidationMetrics).evicted();
	}

	@Test
	@DisplayName("상세 evict는 Redis만 지우고 로컬 캐시는 건드리지 않는다")
	void evict_상세_Redis만() {
		given(redisCacheManager.getCache(anyString())).willReturn(redisCache);

		cacheManager.evict(RedisOnly.INSTANCE, "42");

		verify(redisCache).evict("42");
		verifyNoInteractions(localCacheManager);
	}

	@Test
	@DisplayName("스위치를 끄면 조회는 항상 미스이고 아무것도 적재하지 않는다")
	void 스위치off_항상미스_적재없음() {
		ReflectionTestUtils.setField(cacheManager, "enabled", false);

		String result = cacheManager.getOrPut(TwoTier.INSTANCE, "ALL", String.class, () -> "DB 목록");

		assertThat(result).isEqualTo("DB 목록");
		verifyNoInteractions(localCacheManager, redisCacheManager, lookupMetrics);
		verify(invalidationMetrics, never()).evicted();
	}
}
