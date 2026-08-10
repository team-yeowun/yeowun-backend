package modi.backend.support.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;

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
import modi.backend.infra.cache.RedisPublisher;

/**
 * - 무효화 실패가 지표에 남는지 고정
 *   - 대기열을 만들지 않기로 했으므로 창구가 실패에 대해 하는 일은 "세는 것"뿐
 *   - 그 카운터가 대기열을 뺀 결정의 근거이므로, 조용히 안 오르면 결정 자체가 검증 불가가 됨
 *
 * - 예외 비전파도 함께 봄
 *   - 방송이 실패해도 관리자 수정 요청은 성공해야 함
 */
@ExtendWith(MockitoExtension.class)
class CacheInvalidationMetricsTest {

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
	private Cache localCache;
	@Mock
	private Cache redisCache;

	private MeterRegistry registry;
	private CacheInvalidationMetrics metrics;
	private CacheManager cacheManager;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		metrics = new CacheInvalidationMetrics(registry);
		cacheManager = new CacheManager(localCacheManager, redisCacheManager, redisPublisher, metrics);
		lenient().when(localCacheManager.getCache(anyString())).thenReturn(localCache);
		lenient().when(redisCacheManager.getCache(anyString())).thenReturn(redisCache);
	}

	private double publishCount(String result) {
		return registry.counter("modi.cache.invalidation.publish", "result", result).count();
	}

	@Test
	@DisplayName("발행에 성공하면 success로 센다")
	void 발행성공_계측() {
		cacheManager.evict(TestCache.INSTANCE, "ALL");

		assertThat(publishCount("success")).isEqualTo(1);
		assertThat(publishCount("failure")).isZero();
	}

	@Test
	@DisplayName("Redis가 죽어 발행에 실패해도 예외는 나가지 않고 failure로 센다")
	void 발행실패_계측() {
		willThrow(new RuntimeException("Redis down")).given(redisPublisher).publish(anyString());

		assertThatCode(() -> cacheManager.evict(TestCache.INSTANCE, "ALL")).doesNotThrowAnyException();

		assertThat(publishCount("failure")).isEqualTo(1);
		assertThat(publishCount("success")).isZero();
	}

	@Test
	@DisplayName("L2 삭제가 실패해도 방송은 시도하고 결과를 센다")
	void L2실패여도_방송시도() {
		willThrow(new RuntimeException("Redis down")).given(redisCache).evict(anyString());

		assertThatCode(() -> cacheManager.evict(TestCache.INSTANCE, "ALL")).doesNotThrowAnyException();

		assertThat(publishCount("success")).isEqualTo(1);
	}

	@Test
	@DisplayName("refresh의 방송도 같은 카운터에 남는다 — 워밍이 조용히 실패하지 않게")
	void refresh_계측() {
		cacheManager.refresh(TestCache.INSTANCE, "ALL", "새 목록");

		assertThat(publishCount("success")).isEqualTo(1);
	}

}
