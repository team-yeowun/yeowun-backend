package modi.backend.ingestionv2.collect;

import modi.backend.ingestionv2.collect.domain.CatalogItem;

/** 수집 케이스가 공유하는 목록 항목 픽스처. 공통 토대에 올리지 않는다(공용 계층이 격벽 어휘를 모르게). */
final class CollectFixtures {

	private CollectFixtures() {
	}

	static CatalogItem catalogItem(String vendorKey) {
		return new CatalogItem(
				vendorKey, "여운 기획전", "2026-08-01", "2026-12-31", "여운 미술관",
				"미술", "서울", "종로구", "https://img.example/thumb.jpg",
				"126.97", "37.57", "전시", "https://exh.example/1001");
	}
}
