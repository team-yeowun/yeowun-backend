package modi.backend.ingestionv2.inspect;

import static modi.backend.ingestionv2.inspect.InspectFixtures.catalogItem;
import static modi.backend.ingestionv2.inspect.InspectFixtures.found;
import static modi.backend.ingestionv2.inspect.InspectFixtures.foundWithoutHours;
import static modi.backend.ingestionv2.inspect.InspectFixtures.normalItem;
import static modi.backend.ingestionv2.inspect.InspectFixtures.notFound;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.inspect.domain.InspectionNote;
import modi.backend.ingestionv2.inspect.domain.InspectionStatus;

@DisplayName("통과시켜야 하는 것")
class InspectPassIntegrationTest extends InspectRunner {

	@Test
	@DisplayName("지역이 매핑되지 않아도 통과한다")
	void 지역이_매핑되지_않아도_통과한다() {
		// given 코어의 fromAreaText 는 예외를 던지지 않고 ETC 로 떨어뜨린다(정상 결과)
		run(catalogItem(vendorKey, "여운 기획전", "2026-08-01", "2026-12-31", "알 수 없는 지역", "126.97", "37.57"),
				"현대미술", found());

		// then 여기에 반려를 걸면 매핑 규칙에 아직 없는 지방 소도시 전시가 통째로 사라진다
		assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.PASSED);
		assertThat(inspection().notes()).contains(InspectionNote.REGION_UNMAPPED);
	}

	@Test
	@DisplayName("좌표가 파싱되지 않아도 통과한다")
	void 좌표가_파싱되지_않아도_통과한다() {
		// given 좌표가 깨지면 지도에 핀이 찍히지 않을 뿐 전시는 정상 노출된다
		run(catalogItem(vendorKey, "여운 기획전", "2026-08-01", "2026-12-31", "서울", "좌표없음", "37.57"),
				"현대미술", found());

		// then
		assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.PASSED);
		assertThat(inspection().notes()).contains(InspectionNote.COORDINATE_UNPARSABLE);
	}

	@Test
	@DisplayName("전시장을 찾지 못해도 통과한다")
	void 전시장을_찾지_못해도_통과한다() {
		// given 코어의 PlaceHoursStatus 가 NOT_FOUND 를 정상 케이스로 나누어 두었다
		run(normalItem(vendorKey), "현대미술", notFound());

		// then TINYINT(1) 을 Boolean 으로 읽지 못하면 이 관찰이 조용히 HOURS_EMPTY 로 기운다
		assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.PASSED);
		assertThat(inspection().notes()).contains(InspectionNote.HOURS_PLACE_NOT_FOUND);
		assertThat(inspection().notes()).doesNotContain(InspectionNote.HOURS_EMPTY);
	}

	@Test
	@DisplayName("개장 시간이 비어도 통과한다")
	void 개장_시간이_비어도_통과한다() {
		// given 영업시간을 내걸지 않는 전시장이 실제로 많다
		run(normalItem(vendorKey), "현대미술", foundWithoutHours());

		// then
		assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.PASSED);
		assertThat(inspection().notes()).contains(InspectionNote.HOURS_EMPTY);
		assertThat(inspection().notes()).doesNotContain(InspectionNote.HOURS_PLACE_NOT_FOUND);
	}

	@Test
	@DisplayName("정상값이면 사유도 기록도 없이 통과한다")
	void 정상값이면_사유도_기록도_없이_통과한다() {
		// given 기준선. 이 테스트가 빨개지면 위의 넷이 무엇 때문에 초록인지 알 수 없다
		runNormal();

		// then 빈 집합이 빈 문자열이 아니라 null 로 저장되는지도 여기서 고정한다
		assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.PASSED);
		assertThat(inspection().getRejectReasonCodes()).isNull();
		assertThat(inspection().getNoteCodes()).isNull();
	}
}
