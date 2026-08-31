package modi.backend.ingestionv2.inspect;

import static modi.backend.ingestionv2.inspect.InspectFixtures.catalogItem;
import static modi.backend.ingestionv2.inspect.InspectFixtures.found;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.inspect.domain.InspectionStatus;

@DisplayName("반려의 특수성")
class InspectEventIntegrationTest extends InspectRunner {

	@Test
	@DisplayName("반려돼도 트랜잭션은 커밋된다")
	void 반려돼도_트랜잭션은_커밋된다() {
		// given
		rejectOne();

		// then 반려를 예외로 던져 롤백하면 사실이 아무 데도 남지 않고 재실행이 끝없이 반복된다
		assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.REJECTED);
		assertThat(inspection().getInspectedAt()).isNotNull();
		assertThat(inspection().rejectReasons()).isNotEmpty();
	}

	@Test
	@DisplayName("반려되면 INSPECTED가 적재되지 않는다")
	void 반려되면_INSPECTED가_적재되지_않는다() {
		// given
		rejectOne();

		// then 아웃박스에 행이 있다는 사실 자체가 통과를 뜻한다는 계약
		assertThat(outboxRepository.findAll())
				.noneMatch(outbox -> outbox.getEventType() == IngestionEventType.INSPECTED);
	}

	@Test
	@DisplayName("통과하면 INSPECTED가 한 행 적재된다")
	void 통과하면_INSPECTED가_한_행_적재된다() {
		// given
		runNormal();

		// then 위 테스트의 대칭. 적재 자체가 사라져도 반려 쪽 단언은 계속 초록이다
		assertThat(outboxRepository.findAll())
				.filteredOn(outbox -> outbox.getEventType() == IngestionEventType.INSPECTED)
				.hasSize(1);
	}

	@Test
	@DisplayName("반려는 격리되지도 미처리로 남지도 않는다")
	void 반려는_격리되지도_미처리로_남지도_않는다() {
		// given
		rejectOne();

		// then 반려는 벤더 데이터에 대한 판단이지 시스템의 실패가 아니다
		assertThat(deadLetterRepository.findAll()).isEmpty();
		for (IngestionStream stream : IngestionStream.values()) {
			assertThat(pendingOf(stream).isEmpty()).isTrue();
		}
	}

	private void rejectOne() {
		run(catalogItem(vendorKey, "여운 기획전", "상시", "2026-12-31", "서울", "126.97", "37.57"),
				"현대미술", found());
	}
}
