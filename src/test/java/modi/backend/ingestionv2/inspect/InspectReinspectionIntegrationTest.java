package modi.backend.ingestionv2.inspect;

import static modi.backend.ingestionv2.inspect.InspectFixtures.catalogItem;
import static modi.backend.ingestionv2.inspect.InspectFixtures.found;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.inspect.domain.InspectErrorCode;
import modi.backend.ingestionv2.inspect.domain.InspectionStatus;
import modi.backend.ingestionv2.inspect.domain.RejectReason;
import modi.backend.support.error.CoreException;

@DisplayName("재점검")
class InspectReinspectionIntegrationTest extends InspectRunner {

	@Test
	@DisplayName("반려된 전시를 재점검하면 같은 사유로 다시 반려된다")
	void 반려된_전시를_재점검하면_같은_사유로_다시_반려된다() {
		// given 원장은 불변이므로 규칙이 그대로면 결과도 같아야 한다
		run(catalogItem(vendorKey, "여운 기획전", "상시", "2026-12-31", "서울", "126.97", "37.57"),
				"현대미술", found());
		Set<RejectReason> before = inspection().rejectReasons();

		// when
		inspectFacade.reinspect(vendorKey);

		// then
		assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.REJECTED);
		assertThat(inspection().rejectReasons()).isEqualTo(before);
		assertThat(outboxRepository.findAll())
				.noneMatch(outbox -> outbox.getEventType() == IngestionEventType.INSPECTED);
	}

	@Test
	@DisplayName("통과한 전시는 재점검이 거부된다")
	void 통과한_전시는_재점검이_거부된다() {
		// given 스테이징이 이미 코어 등록을 마쳤을 수 있다
		runNormal();

		// when & then 점검 결과만 반려로 뒤집히면 두 격벽의 사실이 어긋난다
		assertThatThrownBy(() -> inspectFacade.reinspect(vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(InspectErrorCode.INSPECTION_NOT_REJECTED);
	}
}
