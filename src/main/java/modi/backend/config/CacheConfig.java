package modi.backend.config;

import java.time.Duration;
import java.util.List;

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
import modi.backend.support.cache.CacheType;
import modi.backend.support.cache.MyCache;

/**
 * - 캐시 매니저 조립부
 *   - 두 캐시 매니저가 선언 목록을 순회하며 캐시 이름과 TTL을 등록
 *   - 캐시 선언 자체가 TTL을 가지고 있으므로 이 클래스에서는 값을 읽기만 함
 *   - 새로운 캐시가 추가되어도 이 파일은 수정할 필요가 없음
 *
 * - 선언의 타입이 어느 매니저에 올라갈지를 정함
 *   - L1(Caffeine)에는 TWO_TIER·LOCAL만, L2(Redis)에는 TWO_TIER·REDIS만 등록
 *   - 상세(REDIS)는 L1에 등록되지 않으므로 로컬 복사본이 생길 수 없음
 *
 * - Redis TTL은 선언 값 그대로 씀(흩뜨림 없음)
 *   - 목록 키는 7개뿐이고 각자 첫 조회 시점에 적재되어, 한꺼번에 만료될 일이 없음
 *   - 30분 TTL에 분 단위 흩뜨림을 더하면 "삭제 누락 키가 남는 최대 시간"이 선언과 달라짐
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

	@Bean
	public CaffeineCacheManager localCacheManager() {
		CaffeineCacheManager manager = new CaffeineCacheManager();
		// 정적 모드 — 등록하지 않은 이름으로는 L1을 만들지 않는다(상세가 실수로 L1에 담기는 것을 막는다).
		// 등록보다 먼저 불러야 한다: 나중에 부르면 이름마다 기본 캐시(TTL 없음)로 덮어쓴다.
		manager.setCacheNames(List.of());
		for (MyCache cache : ExhibitionCache.ALL) {
			if (cache.getType() == CacheType.REDIS) {
				continue; // 로컬 복사본을 두지 않는 캐시
			}
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
			Duration ttl = redisTtlOf(cache);
			if (ttl == null) {
				continue; // L1만 쓰는 캐시
			}
			builder.withCacheConfiguration(cache.getName(),
					RedisCacheConfiguration.defaultCacheConfig()
							.prefixCacheNameWith("yeowun:")
							.entryTtl(ttl)
							.disableCachingNullValues()
							// 값 타입에 바인딩한 직렬화기 — 선언이 타입을 들고 있어 가능하다.
							.serializeValuesWith(SerializationPair.fromSerializer(
									new JacksonJsonRedisSerializer<>(objectMapper, cache.getValueType()))));
		}
		return builder.build();
	}

	/** 선언의 Redis TTL. TWO_TIER는 L2 TTL, REDIS는 선언 TTL, LOCAL은 Redis를 쓰지 않아 null. */
	static Duration redisTtlOf(MyCache cache) {
		return switch (cache.getType()) {
			case TWO_TIER -> ((MyCache.TwoTierCache) cache).getRedisTtl();
			case REDIS -> cache.getTtl();
			case LOCAL -> null;
		};
	}
}
