package modi.backend.ingestionv2.stage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.stage.domain.StagingStatus;

@DisplayName("종결")
class StageTerminationIntegrationTest extends StageTestSupport {

	@Test
	@DisplayName("ST-E1 STAGED 는 아웃박스에만 남고 스트림으로 나가지 않는다")
	void STAGED는_아웃박스에만_남는다() {
		// given
		seedReadyLedger(vendorKey);
		stageFacade.stage(vendorKey);
		long lengthBefore = lengthOf(IngestionStream.DB);

		// when 발송을 한 틱 돌린다
		outboxDispatcher.dispatchPending();

		// then 꺼내 갈 컨슈머가 없는 항목이 미처리 목록을 채우지 않는다
		Outbox staged = outboxRepository.findAll().stream()
				.filter(outbox -> outbox.getEventType() == IngestionEventType.STAGED)
				.findFirst()
				.orElseThrow();
		assertThat(staged.getStatus()).isEqualTo(OutboxStatus.SENT);
		assertThat(staged.getRetryCount()).isZero();
		assertThat(lengthOf(IngestionStream.DB)).isEqualTo(lengthBefore);
	}

	@Test
	@DisplayName("ST-E2 반영이 실패하면 상태가 남고 예외가 배달 계층으로 되돌아간다")
	void 반영이_실패하면_상태가_남고_예외가_되돌아간다() {
		// given 장르 원장이 없어 조립이 멈춘다
		seedListing(vendorKey, placeName, "2026-08-01", "2026-12-31");
		outboxAppender.append(IngestionEventType.INSPECTED, vendorKey);
		outboxDispatcher.dispatchPending();

		// when 실물 소비 경로로 한 틱 민다
		consumeOnce(properties.consumerName());

		// then 핸들러가 예외를 삼키면 배달 계층이 처리 확인을 해 버려 그 전시가 조용히 누락된다
		assertThat(stagingRow(vendorKey))
				.containsEntry("attempts", 1)
				.containsEntry("status", StagingStatus.PENDING.name());
		assertThat(pendingOf(IngestionStream.DB).size()).isEqualTo(1);
	}
}
