package modi.backend.ingestionv2.common.queue;

import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.support.error.CoreException;

/**
 * 발행 포트의 Redis Streams 어댑터.
 *
 * <ul>
 *   <li>XADD 한 번이 전부 - 발송 트랜잭션을 짧게 유지하는 근거</li>
 *   <li>레코드는 payload 필드 하나 - 아웃박스 payload 컬럼이 그대로 실린다</li>
 *   <li>배정 스트림이 없으면 예외 - 이 지점까지 온 것 자체가 발송 판단의 오류</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamDispatcher implements EventDispatcher {

	private final StringRedisTemplate redisTemplate;

	@Override
	public void dispatch(OutboxPayload payload) {
		IngestionStream stream = IngestionStream.of(payload.eventType())
				.orElseThrow(() -> new CoreException(IngestionErrorCode.STREAM_NOT_ROUTED,
						"배정된 대기열이 없습니다. type=" + payload.eventType()));

		StringRecord record = StreamRecords.newRecord()
				.in(stream.key())
				.ofStrings(EventRecord.of(payload).toFields());

		RecordId recordId = streamOperations().add(record);
		if (recordId == null) {
			throw new CoreException(IngestionErrorCode.STREAM_PUBLISH_FAILED,
					"대기열 응답이 비어 있습니다. stream=" + stream.key());
		}
		log.debug("이벤트를 발행했습니다. stream={} eventId={} type={} aggregateId={} recordId={}",
				stream.key(), payload.eventId(), payload.eventType(), payload.aggregateId(), recordId);
	}

	private StreamOperations<String, String, String> streamOperations() {
		return redisTemplate.opsForStream();
	}
}
