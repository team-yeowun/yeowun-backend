package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;

@DisplayName("소비자 없는 종결")
class StagedTerminationTest extends DeliveryTestSupport {

	@Test
	@DisplayName("STAGED는 대기열로 나가지 않는다")
	void STAGED는_대기열로_나가지_않는다() {
		// given
		outboxAppender.append(IngestionEventType.STAGED, vendorKey);

		// when
		outboxDispatcher.dispatchPending();

		// then 네 스트림의 길이 합이 0이다
		long total = 0;
		for (IngestionStream stream : IngestionStream.values()) {
			total += lengthOf(stream);
		}
		assertThat(total).isZero();
	}

	@Test
	@DisplayName("STAGED는 발송 단계에서 곧바로 SENT로 닫힌다")
	void STAGED는_발송_단계에서_곧바로_SENT로_닫힌다() {
		// given
		outboxAppender.append(IngestionEventType.STAGED, vendorKey);

		// when
		outboxDispatcher.dispatchPending();

		// then 선점한 엔티티를 그대로 닫았으므로 다음 틱이 다시 집지 않는다
		Outbox staged = outboxRepository.findAll().getFirst();
		assertThat(staged.getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(staged.getSentAt()).isNotNull();
		assertThat(staged.getRetryCount()).isZero();
		assertThat(outboxDispatcher.dispatchPending()).isZero();
	}

	@Test
	@DisplayName("STAGED에는 핸들러가 있어도 실행되지 않는다")
	void STAGED에는_핸들러가_있어도_실행되지_않는다() {
		// given STAGED 를 맡는 기록용 핸들러가 등록되어 있다
		outboxAppender.append(IngestionEventType.STAGED, vendorKey);

		// when
		drainAll();

		// then 스트림을 거치지 않으므로 라우팅 자체가 없다
		assertThat(recordingStagedHandler.received()).isEmpty();
	}
}
