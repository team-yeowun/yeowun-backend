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
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.ObjectMapper;

import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.infra.cache.RedisMessageListener;
import modi.backend.infra.cache.RedisPublisher;
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

	/**
	 * - 무효화 채널 구독
	 *   - 전용 커넥션으로 채널 하나를 구독하다가 메시지가 오면 리스너를 부름
	 *   - 커넥션이 끊기면 컨테이너가 스스로 재구독함(끊긴 동안의 메시지는 복구되지 않음)
	 *
	 * - 리스너를 {@code MessageListenerAdapter}로 감싸지 않고 직접 등록함
	 *   - 컨테이너는 구독 확인 콜백을 등록된 리스너가 {@code SubscriptionListener}일 때만 보냄
	 *   - 어댑터는 그 인터페이스를 구현하지 않아, 감싸는 순간 재구독 시 L1 전체 삭제가 조용히 죽음
	 *   - 메시지 수신은 그대로 동작해서 테스트로도 잘 드러나지 않는 함정
	 *
	 * - {@code container.start()}를 부르지 않음
	 *   - 컨테이너가 {@code SmartLifecycle}이라 스프링이 기동 때 알아서 시작함
	 */
	@Bean
	public RedisMessageListenerContainer cacheInvalidationListenerContainer(
			RedisConnectionFactory factory, RedisMessageListener listener, MeterRegistry meterRegistry) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(factory);
		// 토픽 상수는 RedisPublisher가 단일 출처다 — 발행과 구독이 같은 값을 보는 것이 요점이다.
		container.addMessageListener(listener, ChannelTopic.of(RedisPublisher.TOPIC));
		// "서버는 떠 있는데 구독만 끊긴" 상태를 밖에서 보게 하는 유일한 지표(1=구독 중, 0=끊김).
		Gauge.builder("modi.cache.invalidation.subscribed", container, c -> c.isListening() ? 1 : 0)
				.description("이 인스턴스가 무효화 채널을 구독 중인가")
				.register(meterRegistry);
		return container;
	}
}
