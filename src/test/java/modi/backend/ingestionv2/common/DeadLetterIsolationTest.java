package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamRecords;

import modi.backend.ingestionv2.common.deadletter.DeadLetter;
import modi.backend.ingestionv2.common.deadletter.DeadLetterStatus;
import modi.backend.ingestionv2.common.event.IngestionAggregateType;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

@DisplayName("격리")
class DeadLetterIsolationTest extends DeliveryTestSupport {

	@Test
	@DisplayName("재시도 상한을 넘기면 격리하고 미처리 목록에서도 뺀다")
	void 상한을_넘기면_격리하고_확인한다() {
		// given 도메인이 상한 소진을 알려 오는 상황
		recordingCollectedHandler.behaveWith(key -> {
			throw new CoreException(IngestionErrorCode.RETRY_EXHAUSTED, "장르 분류를 세 번 시도했습니다.");
		});
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		drainAll();

		// then 격리 행이 남고
		List<DeadLetter> isolated = deadLetterRepository.findAll();
		assertThat(isolated).hasSize(1);
		assertThat(isolated.getFirst().getAggregateId()).isEqualTo(vendorKey);
		assertThat(isolated.getFirst().getAggregateType()).isEqualTo(IngestionAggregateType.COLLECTION);
		assertThat(isolated.getFirst().getPayload()).isEqualTo(outboxRepository.findAll().getFirst().getPayload());
		assertThat(isolated.getFirst().getStreamKey()).isEqualTo(IngestionStream.DB.key());

		// then 원인은 격리 행이 갖고, 아웃박스는 발행 완료 그대로이며, 미처리 목록에서 빠진다
		assertThat(isolated.getFirst().getErrorMessage()).contains("세 번 시도");
		assertThat(isolated.getFirst().getStackTrace()).contains("CoreException");
		assertThat(isolated.getFirst().getFailedStep()).isEqualTo("RecordingEventHandler");
		assertThat(isolated.getFirst().getRetryCount()).isEqualTo(properties.maxAttempts());
		assertThat(isolated.getFirst().getStatus()).isEqualTo(DeadLetterStatus.PENDING);
		Outbox outbox = outboxRepository.findAll().getFirst();
		assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("상한 소진이 아닌 실패는 격리하지 않고 미처리로 남긴다")
	void 상한_소진이_아닌_실패는_격리하지_않고_미처리로_남긴다() {
		// given 다른 오류 코드의 예외를 던지는 핸들러
		recordingCollectedHandler.behaveWith(key -> {
			throw new CoreException(ErrorType.INVALID_INPUT, "일시 장애");
		});
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		outboxDispatcher.dispatchPending();
		consumeOnce(properties.consumerName());

		// then 회수 스케줄러가 다시 배정할 수 있도록 미확인으로 남는다
		assertThat(deadLetterRepository.findAll()).isEmpty();
		assertThat(outboxRepository.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(pendingOf(IngestionStream.DB).size()).isEqualTo(1);
	}

	@Test
	@DisplayName("예상하지 못한 예외도 미처리로 남는다")
	void 예상하지_못한_예외도_미처리로_남는다() {
		// given 런타임 예외를 던지는 핸들러
		recordingCollectedHandler.behaveWith(key -> {
			throw new IllegalStateException("널 참조");
		});
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		outboxDispatcher.dispatchPending();
		consumeOnce(properties.consumerName());

		// then 버그 하나가 전시를 격리로 떨어뜨리지 않는다
		assertThat(deadLetterRepository.findAll()).isEmpty();
		assertThat(pendingOf(IngestionStream.DB).size()).isEqualTo(1);
	}

	@Test
	@DisplayName("해석할 수 없는 레코드는 종류와 원천 키 없이 격리된다")
	void 해석할_수_없는_레코드는_종류와_원천_키_없이_격리된다() {
		// given 알 수 없는 종류 문자열을 스트림에 직접 넣는다
		redisTemplate.opsForStream().add(StreamRecords.newRecord()
				.in(IngestionStream.DB.key())
				.ofStrings(Map.of("payload", "{\"aggregateType\":\"COLLECTION\",\"aggregateId\":\"" + vendorKey
						+ "\",\"eventType\":\"NOT_AN_EVENT\"}")));

		// when
		consumeOnce(properties.consumerName());

		// then 몇 번을 다시 해도 같은 결과이므로 곧바로 격리한다
		DeadLetter isolated = deadLetterRepository.findAll().getFirst();
		assertThat(isolated.getEventType()).isNull();
		assertThat(isolated.getAggregateId()).isNull();
		assertThat(isolated.getPayload()).contains("NOT_AN_EVENT");
		assertThat(isolated.getFailedStep()).isEqualTo(DeadLetter.STEP_DECODE);
		assertThat(isolated.getRetryCount()).isZero();
		assertThat(isolated.getStreamKey()).isEqualTo(IngestionStream.DB.key());
		assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
	}
}
