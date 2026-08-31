package modi.backend.ingestionv2.lab.retry;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.queue.EventRecord;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;
import modi.backend.ingestionv2.common.queue.IngestionEventRouter;
import modi.backend.support.error.CoreException;

/**
 * step-05 "유실" before 변형 - 상한은 그대로 두고 <b>격리만 뺀</b> 소비자.
 *
 * <ul>
 *   <li>프로덕션 {@code StreamConsumer} 와 같은 자리에서 같은 판단을 하되, 소진 시 격리 없이 확인(ack)만 한다</li>
 *   <li>확인은 미처리 목록에서 항목을 지우므로 <b>되찾을 수단이 사라진다</b> - 그것이 곧 유실이다</li>
 *   <li>유실 건수를 세는 것이 이 클래스의 존재 이유 - "유실 경로 제거" 주장의 before 비영 증명</li>
 *   <li>프로덕션 코드를 고쳐 만들지 않았다 - 격리를 끄는 스위치를 {@code src/main} 에 두면 그 스위치 자체가
 *       운영 사고의 통로가 된다</li>
 * </ul>
 */
final class NoDeadLetterConsumer {

	private final IngestionEventRouter eventRouter;
	private final StringRedisTemplate redisTemplate;
	private final IngestionProperties properties;
	private final List<String> lost = new ArrayList<>();

	NoDeadLetterConsumer(IngestionEventRouter eventRouter, StringRedisTemplate redisTemplate,
			IngestionProperties properties) {
		this.eventRouter = eventRouter;
		this.redisTemplate = redisTemplate;
		this.properties = properties;
	}

	void onMessage(MapRecord<String, String, String> message) {
		EventRecord event;
		try {
			event = EventRecord.from(message.getValue());
		} catch (CoreException malformed) {
			// 격리가 없으니 해석 불가 레코드도 그대로 사라진다.
			lost.add(message.getId().getValue());
			acknowledge(message);
			return;
		}

		IngestionEventHandler handler = eventRouter.route(event.type()).orElse(null);
		if (handler == null) {
			acknowledge(message);
			return;
		}

		try {
			handler.handle(event.aggregateId());
			acknowledge(message);
		} catch (CoreException failure) {
			if (failure.errorCode() == IngestionErrorCode.RETRY_EXHAUSTED) {
				lost.add(event.aggregateId());
				acknowledge(message);
			}
		} catch (RuntimeException failure) {
			// 그 밖의 실패는 프로덕션과 같이 미확인으로 남긴다 - 갈라지는 지점은 소진 처리 하나뿐이다.
		}
	}

	/** 격리 없이 확인해 버려 되찾을 수 없게 된 항목. */
	List<String> lost() {
		return List.copyOf(lost);
	}

	void reset() {
		lost.clear();
	}

	private void acknowledge(MapRecord<String, String, String> message) {
		redisTemplate.opsForStream().acknowledge(properties.consumerGroup(), message);
	}
}
