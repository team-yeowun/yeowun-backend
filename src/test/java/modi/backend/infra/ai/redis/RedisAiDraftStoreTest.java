package modi.backend.infra.ai.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

import modi.backend.config.AiDraftProperties;
import modi.backend.domain.ai.AiDraft;

/**
 * {@link RedisAiDraftStore} 통합 테스트(Testcontainers-Redis). save→find→delete 라운드트립과 키 분리를 검증한다.
 * (D-4 확정: 저장소는 Redis 단일 — 인메모리 폴백 어댑터가 없으므로 어댑터 검증은 실 Redis로 한다.)
 */
@Testcontainers
class RedisAiDraftStoreTest {

	@Container
	static final GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	private LettuceConnectionFactory connectionFactory;
	private RedisAiDraftStore store;

	@BeforeEach
	void setUp() {
		connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		store = new RedisAiDraftStore(redisTemplate, JsonMapper.builder().build(),
				new AiDraftProperties(Duration.ofHours(1)));
	}

	@AfterEach
	void tearDown() {
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("save → find 라운드트립: 질문+답변+초안을 그대로 복원한다")
	void save_find_라운드트립() {
		AiDraft draft = new AiDraft(List.of("q1", "q2"), List.of(new AiDraft.Qna("q1", "a1")), "초안");

		store.save(1L, 10L, draft);

		Optional<AiDraft> found = store.find(1L, 10L);
		assertThat(found).isPresent();
		assertThat(found.get().questions()).containsExactly("q1", "q2");
		assertThat(found.get().answers()).containsExactly(new AiDraft.Qna("q1", "a1"));
		assertThat(found.get().content()).isEqualTo("초안");
	}

	@Test
	@DisplayName("find — 없는 키는 empty")
	void find_없음() {
		assertThat(store.find(99L, 99L)).isEmpty();
	}

	@Test
	@DisplayName("delete — 삭제 후 조회하면 empty")
	void delete_후조회() {
		store.save(1L, 10L, AiDraft.ofQuestions(List.of("q1")));

		store.delete(1L, 10L);

		assertThat(store.find(1L, 10L)).isEmpty();
	}

	@Test
	@DisplayName("사용자·전시별로 키가 분리된다")
	void 키_분리() {
		store.save(1L, 10L, AiDraft.ofQuestions(List.of("A")));
		store.save(2L, 10L, AiDraft.ofQuestions(List.of("B")));

		assertThat(store.find(1L, 10L)).get().extracting(AiDraft::questions).isEqualTo(List.of("A"));
		assertThat(store.find(2L, 10L)).get().extracting(AiDraft::questions).isEqualTo(List.of("B"));
	}
}
