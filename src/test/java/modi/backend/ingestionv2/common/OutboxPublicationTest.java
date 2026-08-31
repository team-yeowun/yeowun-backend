package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionAggregateType;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;

@DisplayName("발행 상태 전이")
class OutboxPublicationTest extends DeliveryTestSupport {

	@Test
	@DisplayName("적재 직후에는 PENDING이고 발송 시각이 비어 있다")
	void 적재_직후에는_PENDING이다() {
		// given
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		Outbox outbox = outboxRepository.findAll().getFirst();

		// then
		assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(outbox.getSentAt()).isNull();
	}

	@Test
	@DisplayName("발송하면 SENT로 바뀌고 발송 횟수가 1이 된다")
	void 발송하면_SENT로_바뀌고_발송_횟수가_1이_된다() {
		// given
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		outboxDispatcher.dispatchPending();

		// then
		Outbox outbox = outboxRepository.findAll().getFirst();
		assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(outbox.getRetryCount()).isEqualTo(1);
		assertThat(outbox.getSentAt()).isNotNull();
		assertThat(lengthOf(IngestionStream.DB)).isEqualTo(1);
	}

	@Test
	@DisplayName("소비가 끝나도 아웃박스는 SENT 그대로다 - 처리 확인은 스트림 미처리 목록의 몫")
	void 소비가_끝나도_아웃박스는_SENT_그대로다() {
		// given
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		drainAll();

		// then
		assertThat(outboxRepository.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey);
	}

	@Test
	@DisplayName("적재 시 애그리거트 좌표와 payload가 함께 채워진다")
	void 적재_시_애그리거트_좌표와_payload가_함께_채워진다() {
		// given
		outboxAppender.append(IngestionEventType.GENRE_READY, vendorKey);

		// when
		Outbox outbox = outboxRepository.findAll().getFirst();

		// then aggregate_type 은 이벤트 종류에서 파생되고 payload 는 컬럼 값과 같은 좌표를 담는다
		assertThat(outbox.getAggregateType()).isEqualTo(IngestionAggregateType.ENRICHMENT);
		assertThat(outbox.getAggregateId()).isEqualTo(vendorKey);
		OutboxPayload payload = outbox.toPayload();
		assertThat(payload.eventType()).isEqualTo(IngestionEventType.GENRE_READY);
		assertThat(payload.aggregateType()).isEqualTo(IngestionAggregateType.ENRICHMENT);
		assertThat(payload.aggregateId()).isEqualTo(vendorKey);
		assertThat(payload.occurredAt()).isEqualTo(outbox.getCreatedAt());
	}

	@Test
	@DisplayName("이미 보낸 행은 다음 발송 틱이 다시 집지 않는다")
	void 이미_보낸_행은_다음_발송_틱이_다시_집지_않는다() {
		// given
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		outboxDispatcher.dispatchPending();
		long lengthAfterFirst = lengthOf(IngestionStream.DB);

		// when
		int claimed = outboxDispatcher.dispatchPending();

		// then
		assertThat(claimed).isZero();
		assertThat(lengthOf(IngestionStream.DB)).isEqualTo(lengthAfterFirst);
	}

	@Test
	@DisplayName("이벤트는 배정표대로 네 대기열에 갈린다")
	void 이벤트는_배정표대로_네_대기열에_갈린다() {
		// given 벤더가 다른 네 종류의 사실
		outboxAppender.append(IngestionEventType.DETAIL_READY, vendorKey);
		outboxAppender.append(IngestionEventType.GENRE_READY, vendorKey);
		outboxAppender.append(IngestionEventType.HOURS_READY, vendorKey);
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// when
		outboxDispatcher.dispatchPending();

		// then 벤더 기준으로 나뉜 네 스트림에 하나씩 들어간다
		assertThat(lengthOf(IngestionStream.CULTURE)).isEqualTo(1);
		assertThat(lengthOf(IngestionStream.AI)).isEqualTo(1);
		assertThat(lengthOf(IngestionStream.GOOGLE)).isEqualTo(1);
		assertThat(lengthOf(IngestionStream.DB)).isEqualTo(1);
	}
}
