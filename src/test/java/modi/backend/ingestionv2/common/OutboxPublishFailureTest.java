package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.EventDispatcher;
import modi.backend.support.error.CoreException;

/**
 * 발행 실패 경로. 대기열이 응답하지 않는 상황을 발행 포트 스텁으로 만든다.
 */
@DisplayName("발행 실패")
class OutboxPublishFailureTest extends DeliveryTestSupport {

	@MockitoBean private EventDispatcher eventDispatcher;

	@Test
	@DisplayName("발행에 실패하면 시도 횟수만 오르고 PENDING으로 남아 다음 틱이 다시 집는다")
	void 발행에_실패하면_PENDING으로_남는다() {
		// given 대기열이 죽어 있다
		willThrow(new CoreException(IngestionErrorCode.STREAM_PUBLISH_FAILED)).given(eventDispatcher).dispatch(any());
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		int claimed = outboxDispatcher.dispatchPending();

		// then 배치가 되돌아가지 않고 행만 표시된다
		assertThat(claimed).isEqualTo(1);
		Outbox outbox = outboxRepository.findAll().getFirst();
		assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(outbox.getRetryCount()).isEqualTo(1);
		assertThat(outbox.getSentAt()).isNull();
		assertThat(outboxDispatcher.dispatchPending()).isEqualTo(1);
	}

	@Test
	@DisplayName("재시도 상한에 닿으면 FAILED로 걷어내고 더는 집지 않는다")
	void 상한에_닿으면_FAILED로_걷어낸다() {
		// given 대기열이 계속 죽어 있다
		willThrow(new CoreException(IngestionErrorCode.STREAM_PUBLISH_FAILED)).given(eventDispatcher).dispatch(any());
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when 상한만큼 틱을 민다
		for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
			outboxDispatcher.dispatchPending();
		}

		// then
		Outbox outbox = outboxRepository.findAll().getFirst();
		assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
		assertThat(outbox.getRetryCount()).isEqualTo(properties.maxAttempts());
		assertThat(outboxDispatcher.dispatchPending()).isZero();
	}

	@Test
	@DisplayName("실패 행 하나가 같은 배치의 다른 행 발행을 막지 않는다")
	void 실패_행_하나가_다른_행_발행을_막지_않는다() {
		// given 하나만 실패하는 대기열
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey + "-bad");
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey + "-good");
		willThrow(new CoreException(IngestionErrorCode.STREAM_PUBLISH_FAILED)).given(eventDispatcher)
				.dispatch(org.mockito.ArgumentMatchers.argThat(payload -> payload.aggregateId().endsWith("-bad")));

		// when
		outboxDispatcher.dispatchPending();

		// then
		assertThat(outboxRepository.findAll())
				.filteredOn(outbox -> outbox.getAggregateId().endsWith("-good"))
				.singleElement()
				.satisfies(outbox -> assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SENT));
		assertThat(outboxRepository.findAll())
				.filteredOn(outbox -> outbox.getAggregateId().endsWith("-bad"))
				.singleElement()
				.satisfies(outbox -> assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING));
	}
}
