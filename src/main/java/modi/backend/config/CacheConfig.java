package modi.backend.config;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import com.github.benmanes.caffeine.cache.Caffeine;

import tools.jackson.databind.ObjectMapper;

import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.support.cache.MyCache;

/**
 * - 캐시 매니저 조립부
 *   - 두 캐시 매니저가 선언 목록을 순회하며 캐시 이름과 TTL을 등록
 *   - 캐시 선언 자체가 TTL을 가지고 있으므로 이 클래스에서는 값을 읽기만 함
 *   - 새로운 캐시가 추가되어도 이 파일은 수정할 필요가 없음
 *
 * - Spring Cache AOP는 사용하지 않음
 *   - 캐시 접근 로직을 직접 관리하므로 Spring Cache AOP가 필요하지 않음
 *   - {@code @EnableCaching}을 활성화하면 Spring Boot가 별도의 {@code CacheManager} 빈을 생성하려 할 수 있음
 *   - 따라서 현재 캐시 구조에서는 Spring Cache AOP를 활성화하지 않음
 */
@Configuration
public class CacheConfig {

    /** L1 크기 상한 */
	private static final long LOCAL_MAX_SIZE = 1_000L;

	/** L2 TTL에 더하는 흩뜨림 상한(분). 다른 키들과 같은 순간에 만료되는 것을 방지 용도 */
	private static final long JITTER_MAX_MINUTES = 30L;

	@Bean
	public CaffeineCacheManager localCacheManager() {
		CaffeineCacheManager manager = new CaffeineCacheManager();
		for (MyCache cache : ExhibitionCache.ALL) {
			manager.registerCustomCache(cache.getName(),
					Caffeine.newBuilder()
							.maximumSize(LOCAL_MAX_SIZE)
							.expireAfterWrite(cache.getTtl())   // 선언의 L1 TTL
							.recordStats()                      // 히트율 관찰용
							.build());
		}
		return manager;
	}

	@Bean
	public RedisCacheManager redisCacheManager(RedisConnectionFactory factory, ObjectMapper objectMapper) {
		RedisCacheManagerBuilder builder = RedisCacheManager.builder(factory);
		for (MyCache cache : ExhibitionCache.ALL) {
			if (cache instanceof MyCache.TwoTierCache twoTier) {
				// jitter: 워밍이 연속으로 실패했을 때 다른 키들이 같은 순간에 만료되지 않게 흩뜨린다.
				Duration ttl = twoTier.getRedisTtl()
						.plusMinutes(ThreadLocalRandom.current().nextLong(0, JITTER_MAX_MINUTES));

				builder.withCacheConfiguration(cache.getName(),
						RedisCacheConfiguration.defaultCacheConfig()
								.prefixCacheNameWith("yeowun:")
								.entryTtl(ttl)
								.disableCachingNullValues()
								// 값 타입에 바인딩한 직렬화기 — 선언이 타입을 들고 있어 가능하다.
								.serializeValuesWith(SerializationPair.fromSerializer(
										new JacksonJsonRedisSerializer<>(objectMapper, cache.getValueType()))));
			}
		}
		return builder.build();
	}
}
