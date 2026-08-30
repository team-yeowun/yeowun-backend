package modi.backend.ingestionv2.common;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.queue.IngestionStream;

/**
 * 컨슈머 그룹 생성 - 이것이 없으면 컨슈머가 한 건도 읽지 못한다.
 *
 * <ul>
 *   <li>XGROUP CREATE에 MKSTREAM - 최초 기동에는 스트림 키 자체가 없음</li>
 *   <li>BUSYGROUP은 정상 - 두 번째 기동과 두 번째 인스턴스에서 항상 발생</li>
 *   <li>시작 오프셋 0 - 그룹 생성 직전에 들어온 항목을 놓치지 않기 위함</li>
 *   <li>슬라이스 스위치(enabled)에만 걸림 - auto-delivery를 꺼도 그룹은 만들어져야 테스트가 직접 읽을 수 있다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion.v2", name = "enabled", havingValue = "true")
public class StreamGroupInitializer implements InitializingBean {

	private static final String BUSY_GROUP = "BUSYGROUP";

	private final StringRedisTemplate redisTemplate;
	private final IngestionProperties properties;

	@Override
	public void afterPropertiesSet() {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		for (IngestionStream stream : IngestionStream.values()) {
			createGroup(streamOperations, stream);
		}
	}

	private void createGroup(StreamOperations<String, String, String> streamOperations, IngestionStream stream) {
		try {
			streamOperations.createGroup(stream.key(), ReadOffset.from("0"), properties.consumerGroup());
			log.info("컨슈머 그룹을 만들었습니다. stream={} group={}", stream.key(), properties.consumerGroup());
		} catch (RedisSystemException alreadyExists) {
			if (!isBusyGroup(alreadyExists)) {
				throw alreadyExists;
			}
			log.debug("컨슈머 그룹이 이미 있습니다. stream={} group={}", stream.key(), properties.consumerGroup());
		}
	}

	private static boolean isBusyGroup(RedisSystemException exception) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
		return cause.getMessage() != null && cause.getMessage().contains(BUSY_GROUP);
	}
}
