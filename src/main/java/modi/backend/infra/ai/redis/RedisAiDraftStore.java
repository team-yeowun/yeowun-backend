package modi.backend.infra.ai.redis;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import modi.backend.config.AiDraftProperties;
import modi.backend.domain.ai.AiDraft;
import modi.backend.domain.ai.AiDraftStore;

/**
 * {@link AiDraftStore}의 Redis 어댑터. 키 {@code ai:draft:{userId}:{exhibitionId}}에 draft를 JSON으로 TTL과 함께 저장한다.
 * draft 캐시는 부가 기능이므로 Redis 장애 시 예외를 삼켜 degrade한다(save=no-op, find=empty, delete=no-op)
 * — 질문/감상문 생성·저장 등 본 플로우는 그대로 정상 동작. TTL은 {@link AiDraftProperties#ttl()}.
 * JSON 직렬화는 Spring이 자동 구성한 {@link ObjectMapper}(Jackson 3 — Boot 4 기본)를 주입받아 쓴다.
 *   ※ Jackson 2({@code com.fasterxml})도 클래스패스에 있지만 그쪽 ObjectMapper는 <b>빈이 없다</b> —
 *     주입받으면 NoSuchBean으로 전체 컨텍스트가 뜨지 않으므로, 새 코드는 {@code tools.jackson}을 쓴다(GeminiAiChatClient와 동일).
 */
@Component
public class RedisAiDraftStore implements AiDraftStore {

	private static final Logger log = LoggerFactory.getLogger(RedisAiDraftStore.class);
	private static final String KEY_PREFIX = "ai:draft:";

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final AiDraftProperties properties;

	public RedisAiDraftStore(StringRedisTemplate redis, ObjectMapper objectMapper, AiDraftProperties properties) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	@Override
	public void save(Long userId, Long exhibitionId, AiDraft draft) {
		if (userId == null || exhibitionId == null || draft == null) {
			return;
		}
		try {
			String json = objectMapper.writeValueAsString(draft);
			redis.opsForValue().set(key(userId, exhibitionId), json, properties.ttl());
		} catch (Exception e) {
			log.warn("AI draft 캐시 저장 실패 userId={} exhibitionId={}: {}", userId, exhibitionId, e.getMessage());
		}
	}

	@Override
	public Optional<AiDraft> find(Long userId, Long exhibitionId) {
		if (userId == null || exhibitionId == null) {
			return Optional.empty();
		}
		try {
			String json = redis.opsForValue().get(key(userId, exhibitionId));
			if (json == null || json.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(objectMapper.readValue(json, AiDraft.class));
		} catch (Exception e) {
			log.warn("AI draft 캐시 조회 실패 userId={} exhibitionId={}: {}", userId, exhibitionId, e.getMessage());
			return Optional.empty();
		}
	}

	@Override
	public void delete(Long userId, Long exhibitionId) {
		if (userId == null || exhibitionId == null) {
			return;
		}
		try {
			redis.delete(key(userId, exhibitionId));
		} catch (Exception e) {
			log.warn("AI draft 캐시 삭제 실패 userId={} exhibitionId={}: {}", userId, exhibitionId, e.getMessage());
		}
	}

	private String key(Long userId, Long exhibitionId) {
		return KEY_PREFIX + userId + ':' + exhibitionId;
	}
}
