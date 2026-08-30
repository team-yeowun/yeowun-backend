package modi.backend.ingestionv2.common.queue;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionProperties;

/**
 * 스트림 상태 조회 - 서버에 접속하지 않고도 배선을 확인하기 위한 읽기 전용 컴포넌트.
 *
 * <ul>
 *   <li>조회만 함 - 그룹을 만들거나 항목을 건드리지 않음</li>
 *   <li>그룹 조회 실패를 삼킴 - 관리자 화면이 그 한 스트림 때문에 통째로 실패하지 않게</li>
 *   <li>lag은 원시 응답에서 읽는다 - 값이 없으면 비운다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamStatusReader {

	private static final String LAG_FIELD = "lag";

	private final StringRedisTemplate redisTemplate;
	private final IngestionProperties properties;

	public List<StreamStatus> readAll() {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		List<StreamStatus> statuses = new ArrayList<>();
		for (IngestionStream stream : IngestionStream.values()) {
			statuses.add(read(streamOperations, stream));
		}
		return List.copyOf(statuses);
	}

	private StreamStatus read(StreamOperations<String, String, String> streamOperations, IngestionStream stream) {
		long length = orZero(streamOperations.size(stream.key()));
		try {
			for (StreamInfo.XInfoGroup group : streamOperations.groups(stream.key())) {
				if (properties.consumerGroup().equals(group.groupName())) {
					return StreamStatus.of(stream.key(), length, orZero(group.consumerCount()),
							orZero(group.pendingCount()), lagOf(group));
				}
			}
		} catch (RuntimeException groupUnavailable) {
			log.warn("스트림 그룹 정보를 읽지 못했습니다. stream={}", stream.key(), groupUnavailable);
		}
		return StreamStatus.groupMissing(stream.key(), length);
	}

	/** 트리밍 이후 Redis가 계산하지 못하면 이 값이 비어 온다. 오류가 아니라 정상 응답이다. */
	private static Long lagOf(StreamInfo.XInfoGroup group) {
		Object raw = group.getRaw().get(LAG_FIELD);
		return raw instanceof Number number ? number.longValue() : null;
	}

	private static long orZero(Long value) {
		return value == null ? 0L : value;
	}
}
