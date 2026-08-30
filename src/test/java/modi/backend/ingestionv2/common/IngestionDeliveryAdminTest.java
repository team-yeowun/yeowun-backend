package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.deadletter.DeadLetter;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.queue.IngestionStream;

@DisplayName("관리자 조회")
class IngestionDeliveryAdminTest extends DeliveryTestSupport {

	@Test
	@DisplayName("격리 목록은 재주입하지 않은 항목만 오래된 순으로 담는다")
	void 격리_목록은_재주입하지_않은_항목만_오래된_순으로_담는다() {
		// given 격리 셋 중 하나를 재주입해 둔다
		for (int index = 0; index < 3; index++) {
			deadLetterService.isolate(OutboxPayload.of(IngestionEventType.COLLECTED, vendorKey + "-" + index,
					IngestionClock.now()), IngestionStream.DB.key(), "0-" + index, new DeadLetter.Failure("원인", null, "test"), 3);
		}
		DeadLetter first = deadLetterRepository.findAll().getFirst();
		ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(first.getId()));

		// when
		IngestionDeliveryResult.DeadLetters result =
				ingestionDeliveryFacade.findDeadLetters(IngestionDeliveryCriteria.Listing.of(50));

		// then 되돌려 보낸 항목이 계속 보이면 관리자가 같은 것을 두 번 처리한다
		assertThat(result.count()).isEqualTo(2);
		assertThat(result.items()).extracting(IngestionDeliveryResult.DeadLetterItem::aggregateId)
				.containsExactly(vendorKey + "-1", vendorKey + "-2");
	}

	@Test
	@DisplayName("조회 상한은 경계값으로 눕는다")
	void 조회_상한은_경계값으로_눕는다() {
		assertThat(IngestionDeliveryCriteria.Listing.of(0).limit()).isEqualTo(1);
		assertThat(IngestionDeliveryCriteria.Listing.of(500).limit()).isEqualTo(200);
	}

	@Test
	@DisplayName("격리 목록의 이벤트 종류는 비어 있을 수 있다")
	void 격리_목록의_이벤트_종류는_비어_있을_수_있다() {
		// given 해석 불가 격리 행
		deadLetterService.isolateMalformed(IngestionStream.DB.key(), "0-1", "{broken", new DeadLetter.Failure("해석 불가", null, DeadLetter.STEP_DECODE));

		// when
		IngestionDeliveryResult.DeadLetters result =
				ingestionDeliveryFacade.findDeadLetters(IngestionDeliveryCriteria.Listing.of(50));

		// then 널을 가정하지 않으면 관리자 화면이 그 한 행 때문에 통째로 실패한다
		assertThat(result.items()).singleElement()
				.satisfies(item -> {
					assertThat(item.eventType()).isNull();
					assertThat(item.aggregateId()).isNull();
					assertThat(item.payload()).isEqualTo("{broken");
				});
	}

	@Test
	@DisplayName("스트림 상태는 네 스트림의 길이와 미처리 건수를 돌려준다")
	void 스트림_상태는_네_스트림의_길이와_미처리_건수를_돌려준다() {
		// given 항목 하나를 발송해 두고 읽기만 한다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		outboxDispatcher.dispatchPending();
		readAs("peek");

		// when
		IngestionDeliveryResult.Streams streams = ingestionDeliveryFacade.findStreams();

		// then 배선이 끊겼을 때 서버에 접속하지 않고 판단할 유일한 창이다
		assertThat(streams.items()).hasSize(4);
		assertThat(streams.items()).allMatch(IngestionDeliveryResult.StreamItem::groupExists);
		assertThat(streams.items())
				.filteredOn(item -> item.streamKey().equals(IngestionStream.DB.key()))
				.singleElement()
				.satisfies(item -> {
					assertThat(item.length()).isEqualTo(1);
					assertThat(item.pendingCount()).isEqualTo(1);
				});
	}
}
