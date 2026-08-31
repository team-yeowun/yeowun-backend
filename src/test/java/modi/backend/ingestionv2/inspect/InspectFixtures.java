package modi.backend.ingestionv2.inspect;

import modi.backend.ingestionv2.collect.domain.CatalogItem;
import modi.backend.ingestionv2.enrich.domain.detail.DetailData;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceData;

/**
 * 점검 케이스가 공유하는 벤더 응답 픽스처.
 *
 * <p>원장을 직접 INSERT 하지 않고 수집과 보강을 실제로 태워 만든다. 점검이 검증해야 할 것은
 * "이런 단면이 주어지면"이 아니라 "벤더가 이런 값을 보내면"이기 때문이다.
 */
final class InspectFixtures {

	private InspectFixtures() {
	}

	/** 목록 항목. 점검이 읽는 여섯 필드만 인자로 받고 나머지는 고정한다. */
	static CatalogItem catalogItem(String vendorKey, String title, String startDate, String endDate,
			String area, String gpsX, String gpsY) {
		return new CatalogItem(vendorKey, title, startDate, endDate, "여운 미술관", "미술", area,
				"종로구", "https://img.example/thumb.jpg", gpsX, gpsY, "전시", "https://exh.example/1");
	}

	static CatalogItem normalItem(String vendorKey) {
		return catalogItem(vendorKey, "여운 기획전", "2026-08-01", "2026-12-31", "서울", "126.97", "37.57");
	}

	static DetailData detailData() {
		return new DetailData("여운 기획전", "2026-08-01", "2026-12-31", "여운 미술관", "미술", "서울", "종로구",
				"126.97", "37.57", "무료", "전시 설명 원문", "https://exh.example/1", "02-000-0000",
				"https://img.example/detail.jpg", false);
	}

	/** 장소를 찾았고 영업시간도 받은 경우. */
	static PlaceData found() {
		return new PlaceData("place-1", "여운 미술관", "서울 종로구 1-1",
				"{\"weekdayDescriptions\":[\"월요일: 휴관\"]}", false);
	}

	/** 장소는 찾았으나 영업시간이 없는 경우. */
	static PlaceData foundWithoutHours() {
		return new PlaceData("place-1", "여운 미술관", "서울 종로구 1-1", null, false);
	}

	/** 구글이 장소를 찾지 못한 경우. */
	static PlaceData notFound() {
		return PlaceData.notFound();
	}
}
