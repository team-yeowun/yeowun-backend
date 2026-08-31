package modi.backend.ingestionv2.inspect;

import static modi.backend.ingestionv2.inspect.InspectFixtures.catalogItem;
import static modi.backend.ingestionv2.inspect.InspectFixtures.found;
import static modi.backend.ingestionv2.inspect.InspectFixtures.normalItem;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.inspect.domain.InspectionStatus;
import modi.backend.ingestionv2.inspect.domain.RejectReason;

@DisplayName("반려 사유")
class InspectRejectionIntegrationTest extends InspectRunner {

	@Test
	@DisplayName("제목이 비면 반려된다")
	void 제목이_비면_반려된다() {
		// given 제목 없는 전시가 등록되면 목록에 표시할 것이 없는 빈 카드가 노출된다
		run(catalogItem(vendorKey, "  ", "2026-08-01", "2026-12-31", "서울", "126.97", "37.57"),
				"현대미술", found());

		// then
		assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.REJECTED);
		assertThat(inspection().rejectReasons()).contains(RejectReason.TITLE_BLANK);
	}

	@Test
	@DisplayName("시작일이 파싱되지 않으면 반려된다")
	void 시작일이_파싱되지_않으면_반려된다() {
		// given 어셈블의 toDate 가 조용히 null 로 삼키던 값을 여기서 잡는다
		run(catalogItem(vendorKey, "여운 기획전", "상시", "2026-12-31", "서울", "126.97", "37.57"),
				"현대미술", found());

		// then
		assertThat(inspection().rejectReasons()).contains(RejectReason.START_DATE_UNPARSABLE);
	}

	@Test
	@DisplayName("종료일이 파싱되지 않으면 반려된다")
	void 종료일이_파싱되지_않으면_반려된다() {
		// given 종료일이 null 이면 종료된 전시가 영원히 노출 대상으로 남는다
		run(catalogItem(vendorKey, "여운 기획전", "2026-08-01", "2026.12.31", "서울", "126.97", "37.57"),
				"현대미술", found());

		// then
		assertThat(inspection().rejectReasons()).contains(RejectReason.END_DATE_UNPARSABLE);
	}

	@Test
	@DisplayName("종료일이 시작일보다 앞서면 반려된다")
	void 종료일이_시작일보다_앞서면_반려된다() {
		// given 파싱만 보는 검증으로는 잡히지 않는 결함
		run(catalogItem(vendorKey, "여운 기획전", "2026-12-31", "2026-08-01", "서울", "126.97", "37.57"),
				"현대미술", found());

		// then
		assertThat(inspection().rejectReasons()).contains(RejectReason.PERIOD_REVERSED);
	}

	@Test
	@DisplayName("장르 키워드가 비면 반려된다")
	void 장르_키워드가_비면_반려된다() {
		// given AI 가 빈 줄을 돌려준 날 실제로 도착하는 값이다(보강은 분류 성공으로 취급해 통과시킨다)
		run(normalItem(vendorKey), " ", found());

		// then
		assertThat(inspection().rejectReasons()).contains(RejectReason.GENRE_BLANK);
	}

	@Test
	@DisplayName("사유가 여러 개면 전부 기록된다")
	void 사유가_여러_개면_전부_기록된다() {
		// given 제목과 날짜와 장르가 동시에 깨진다
		run(catalogItem(vendorKey, "", "상시", "2026-12-31", "서울", "126.97", "37.57"), "", found());

		// then 첫 사유에서 조기 반환하면 관리자가 고칠 것을 하나씩 발견하게 된다
		assertThat(inspection().rejectReasons())
				.contains(RejectReason.TITLE_BLANK, RejectReason.START_DATE_UNPARSABLE, RejectReason.GENRE_BLANK);
	}
}
