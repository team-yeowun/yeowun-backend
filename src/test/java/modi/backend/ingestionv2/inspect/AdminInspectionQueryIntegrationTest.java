package modi.backend.ingestionv2.inspect;

import static modi.backend.ingestionv2.inspect.InspectFixtures.catalogItem;
import static modi.backend.ingestionv2.inspect.InspectFixtures.found;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.inspect.domain.InspectCriteria;
import modi.backend.ingestionv2.inspect.domain.InspectResult;
import modi.backend.ingestionv2.inspect.domain.RejectReason;

@DisplayName("관리자 조회")
class AdminInspectionQueryIntegrationTest extends InspectRunner {

	@Test
	@DisplayName("반려 목록이 사유로 좁혀진다")
	void 반려_목록이_사유로_좁혀진다() {
		// given 날짜로 반려된 건 하나와 제목으로 반려된 건 하나
		run(catalogItem(vendorKey, "여운 기획전", "상시", "2026-12-31", "서울", "126.97", "37.57"),
				"현대미술", found());
		String titleBroken = vendorKey + "-title";
		vendorKey = titleBroken;
		// 회차 마크는 회차당 하나뿐이라 두 번째 회차를 돌리려면 먼저 비운다(관리자가 마크를 지우는 것과 같은 상황).
		jdbcTemplate.execute("delete from ingestion_collect_batch_mark");
		run(catalogItem(titleBroken, " ", "2026-08-01", "2026-12-31", "서울", "126.97", "37.57"),
				"현대미술", found());

		// when 사유 조건은 콤마 문자열 안을 찾는 함수 템플릿이라 실물 MySQL 위에서만 드러난다
		InspectResult.RejectedPage dateOnly = inspectFacade.findRejected(
				InspectCriteria.RejectedSearch.of(RejectReason.START_DATE_UNPARSABLE, 0, 20));
		InspectResult.RejectedPage all = inspectFacade.findRejected(
				InspectCriteria.RejectedSearch.of(null, 0, 20));

		// then
		assertThat(dateOnly.totalCount()).isEqualTo(1);
		assertThat(all.totalCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("원장이 결손된 전시도 단건 진단이 열린다")
	void 원장이_결손된_전시도_단건_진단이_열린다() {
		// given 반려된 건을 만든 뒤 원장을 지운다(관리자가 이 화면을 여는 가장 큰 이유)
		run(catalogItem(vendorKey, "여운 기획전", "상시", "2026-12-31", "서울", "126.97", "37.57"),
				"현대미술", found());
		jdbcTemplate.execute("delete from ingestion_genre_snapshot");

		// when
		InspectResult.Detail detail = inspectFacade.findDetail(vendorKey);

		// then 여기서 예외를 던지면 가장 봐야 할 전시의 화면만 열리지 않는다
		assertThat(detail.inspection().vendorKey()).isEqualTo(vendorKey);
		assertThat(detail.ledger()).isNull();
	}

	@Test
	@DisplayName("단건 진단은 점검 결과와 원장 단면을 함께 돌려준다")
	void 단건_진단은_점검_결과와_원장_단면을_함께_돌려준다() {
		// given 사유만으로는 조치를 정할 수 없다(END_DATE_UNPARSABLE 이 "상시"인지 빈 문자열인지)
		run(catalogItem(vendorKey, "여운 기획전", "2026-08-01", "상시", "서울", "126.97", "37.57"),
				"현대미술", found());

		// when
		InspectResult.Detail detail = inspectFacade.findDetail(vendorKey);

		// then
		assertThat(detail.inspection().rejectReasons()).contains(RejectReason.END_DATE_UNPARSABLE);
		assertThat(detail.ledger()).isNotNull();
		assertThat(detail.ledger().endDate()).isEqualTo("상시");
		assertThat(detail.ledger().openingHoursPresent()).isTrue();
	}
}
