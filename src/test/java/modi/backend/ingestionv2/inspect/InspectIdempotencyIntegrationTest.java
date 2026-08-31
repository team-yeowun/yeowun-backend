package modi.backend.ingestionv2.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;

@DisplayName("멱등")
class InspectIdempotencyIntegrationTest extends InspectRunner {

	@Test
	@DisplayName("같은 이벤트가 두 번 와도 점검 행은 하나다")
	void 같은_이벤트가_두_번_와도_점검_행은_하나다() {
		// given 한 번 점검을 끝낸다
		runNormal();
		LocalDateTime firstInspectedAt = inspection().getInspectedAt();

		// when 같은 사실이 한 번 더 도착한다
		handle(IngestionEventType.ENRICHED, vendorKey);

		// then 조기 반환이 사라지면 두 번째 전달이 판정을 덮어쓴다
		assertThat(inspectionRepository.count()).isEqualTo(1);
		assertThat(inspection().getInspectedAt()).isEqualTo(firstInspectedAt);
	}

	@Test
	@DisplayName("중복 전달에도 INSPECTED가 두 번 적재되지 않는다")
	void 중복_전달에도_INSPECTED가_두_번_적재되지_않는다() {
		// given
		runNormal();

		// when
		handle(IngestionEventType.ENRICHED, vendorKey);

		// then 조기 반환이 깨지면 스테이징이 같은 전시로 두 번 깨어난다
		assertThat(outboxRepository.findAll())
				.filteredOn(outbox -> outbox.getEventType() == IngestionEventType.INSPECTED)
				.hasSize(1);
	}
}
