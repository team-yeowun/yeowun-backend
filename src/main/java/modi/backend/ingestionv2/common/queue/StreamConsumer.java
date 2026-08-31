package modi.backend.ingestionv2.common.queue;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.support.error.CoreException;

/**
 * 소비 어댑터 - 트랜잭션 없이 실행하고 결과에 따라 확인하거나 남긴다.
 *
 * <ul>
 *   <li>처리 대상은 레코드의 payload에서 읽는다 - 아웃박스 테이블을 다시 조회하지 않음</li>
 *   <li>트랜잭션을 열지 않음 - 도메인 핸들러가 자기 경계를 스스로 정하게 함</li>
 *   <li>성공은 확인(ack), 소진은 확인 후 오류 로그, 그 밖의 실패는 미확인으로 남김</li>
 *   <li>소진 판정은 도메인이 보낸 오류 코드 하나로만 - 도메인 클래스를 알지 못함</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

	private final IngestionEventRouter eventRouter;
	private final StringRedisTemplate redisTemplate;
	private final IngestionProperties properties;

	@Override
	public void onMessage(MapRecord<String, String, String> message) {
		EventRecord event;
		try {
			event = EventRecord.from(message.getValue());
		} catch (CoreException malformed) {
			log.error("배달 레코드를 해석할 수 없어 처리 확인만 하고 넘깁니다. stream={} recordId={}",
					message.getStream(), message.getId(), malformed);
			acknowledge(message);
			return;
		}

		IngestionEventHandler handler = eventRouter.route(event.type()).orElse(null);
		if (handler == null) {
			log.info("맡는 곳이 없는 이벤트라 그대로 종결합니다. type={} aggregateId={}", event.type(), event.aggregateId());
			acknowledge(message);
			return;
		}

		execute(handler, event, message);
	}

	private void execute(IngestionEventHandler handler, EventRecord event,
			MapRecord<String, String, String> message) {
		try {
			handler.handle(event.aggregateId());
			acknowledge(message);
		} catch (CoreException failure) {
			if (failure.errorCode() == IngestionErrorCode.RETRY_EXHAUSTED) {
				acknowledge(message);
				log.error("재시도 상한을 넘겨 처리 확인만 하고 넘깁니다. type={} aggregateId={} step={}", event.type(),
						event.aggregateId(), handler.getClass().getSimpleName());
				return;
			}
			log.warn("이벤트 처리에 실패해 미확인으로 남깁니다. type={} aggregateId={}",
					event.type(), event.aggregateId(), failure);
		} catch (RuntimeException failure) {
			log.warn("이벤트 처리 중 예상하지 못한 오류가 발생했습니다. type={} aggregateId={}",
					event.type(), event.aggregateId(), failure);
		}
	}

	private void acknowledge(MapRecord<String, String, String> message) {
		streamOperations().acknowledge(properties.consumerGroup(), message);
	}

	private StreamOperations<String, String, String> streamOperations() {
		return redisTemplate.opsForStream();
	}
}
