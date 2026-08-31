package modi.backend.ingestionv2.common.queue;

import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionProperties;

/**
 * 스트림 길이 관리.
 *
 * <ul>
 *   <li>Streams는 로그 구조라 처리 확인해도 항목이 남는다 - 상한 관리가 곧 메모리 관리</li>
 *   <li>근사 자르기 사용 - 정확한 길이보다 자르는 비용이 중요</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamTrimmer {

	private final StringRedisTemplate redisTemplate;
	private final IngestionProperties properties;

	public void trimAll() {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		for (IngestionStream stream : IngestionStream.values()) {
			Long removed = streamOperations.trim(stream.key(), properties.streamMaxLength(), true);
			if (removed != null && removed > 0) {
				log.info("스트림을 잘라냈습니다. stream={} removed={}", stream.key(), removed);
			}
		}
	}
}
