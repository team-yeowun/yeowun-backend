package modi.backend.infra.exhibition.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 조회수 누산기 어댑터의 계약을 실제 Redis에 대고 고정한다. 지켜야 하는 성질은 셋이다 —
 * 누적된다 · 수거는 원자적이라 두 인스턴스가 같은 델타를 두 번 반영하지 않는다 · 반영 실패분은 되돌려 유실되지 않는다.
 */
@Testcontainers
class RedisExhibitionViewCounterTest {

	@Container
	@SuppressWarnings("resource")
	private static final GenericContainer<?> REDIS =
			new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	private RedisExhibitionViewCounter counter;

	@BeforeEach
	void setUp() {
		StringRedisTemplate redisTemplate = templateFor(REDIS.getHost(), REDIS.getMappedPort(6379));
		redisTemplate.delete(RedisExhibitionViewCounter.DELTA_KEY);
		redisTemplate.delete(RedisExhibitionViewCounter.DRAINING_KEY);
		counter = new RedisExhibitionViewCounter(redisTemplate);
	}

	private static StringRedisTemplate templateFor(String host, int port) {
		LettuceConnectionFactory factory =
				new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
		factory.afterPropertiesSet();
		StringRedisTemplate template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		return template;
	}

	@Test
	@DisplayName("increase는 누적 델타를 돌려주고, drain은 전량을 수거한 뒤 새 창을 연다")
	void increase_그리고_drain() {
		assertThat(counter.increase(127L)).isEqualTo(1);
		assertThat(counter.increase(127L)).isEqualTo(2);
		counter.increase(999L);

		assertThat(counter.drain()).containsExactlyInAnyOrderEntriesOf(Map.of(127L, 2L, 999L, 1L));
		assertThat(counter.increase(127L)).isEqualTo(1);
	}

	@Test
	@DisplayName("두 번째 drain은 빈 결과다 — 두 인스턴스가 같은 델타를 두 번 반영하지 않는다")
	void drain_동시수거_한쪽만가져간다() {
		counter.increase(127L);

		assertThat(counter.drain()).containsEntry(127L, 1L);
		assertThat(counter.drain()).isEmpty();
	}

	@Test
	@DisplayName("restoreDrained는 수거분을 되돌리고 그 사이 들어온 조회와 합산한다 — 반영 실패가 유실이 되지 않는다")
	void restoreDrained_되돌리고_합산() {
		counter.increase(127L);
		counter.drain();
		counter.increase(127L);

		counter.restoreDrained();

		assertThat(counter.drain()).containsEntry(127L, 2L);
	}

	@Test
	@DisplayName("Redis가 죽어 있어도 increase는 예외 없이 0이다 — 조회수 때문에 상세 API가 죽지 않는다")
	void increase_redis장애_예외없이0() {
		RedisExhibitionViewCounter dead = new RedisExhibitionViewCounter(templateFor("127.0.0.1", 1));

		assertThat(dead.increase(127L)).isZero();
	}
}
