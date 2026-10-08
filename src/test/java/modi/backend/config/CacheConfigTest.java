package modi.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import tools.jackson.databind.json.JsonMapper;

import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.support.cache.MyCache;

/**
 * - 조립부가 선언대로 두 매니저를 채우는지 고정
 *   - L1(Caffeine)에는 목록만 — 상세 이름으로는 L1이 아예 만들어지지 않아야 함
 *   - Redis TTL은 선언 값 그대로(흩뜨림 없음) — 30분이 "삭제 누락 키가 남는 최대 시간"이라는 설계가 숫자로 지켜지는지
 */
class CacheConfigTest {

	private final CacheConfig config = new CacheConfig();

	@Test
	@DisplayName("L1에는 목록 7종만 등록되고, 상세 이름으로는 L1이 생기지 않는다")
	void L1_목록만() {
		CaffeineCacheManager local = config.localCacheManager();

		assertThat(local.getCacheNames()).containsExactlyInAnyOrderElementsOf(
				ExhibitionCache.LISTS.stream().map(MyCache::getName).toList());
		assertThat(local.getCache(ExhibitionCache.ExhibitionDetail.INSTANCE.getName())).isNull();
	}

	@Test
	@DisplayName("L1 목록의 만료는 10분이다")
	void L1_TTL_10분() {
		CaffeineCacheManager local = config.localCacheManager();

		for (MyCache list : ExhibitionCache.LISTS) {
			CaffeineCache cache = (CaffeineCache) local.getCache(list.getName());
			Duration ttl = cache.getNativeCache().policy().expireAfterWrite().orElseThrow().getExpiresAfter();
			assertThat(ttl).as(list.getName()).isEqualTo(Duration.ofMinutes(10));
		}
	}

	@Test
	@DisplayName("Redis TTL은 목록·상세 모두 정확히 30분이다(흩뜨림 없음)")
	void Redis_TTL_30분() {
		RedisCacheManager redis = config.redisCacheManager(mock(RedisConnectionFactory.class),
				JsonMapper.builder().build());
		redis.initializeCaches();

		for (MyCache cache : ExhibitionCache.ALL) {
			RedisCache redisCache = (RedisCache) redis.getCache(cache.getName());
			Duration ttl = redisCache.getCacheConfiguration().getTtlFunction().getTimeToLive("ALL", "값");
			assertThat(ttl).as(cache.getName()).isEqualTo(Duration.ofMinutes(30));
		}
	}
}
