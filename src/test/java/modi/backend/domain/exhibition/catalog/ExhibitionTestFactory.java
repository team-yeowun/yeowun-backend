package modi.backend.domain.exhibition.catalog;

import java.time.LocalDate;

import modi.backend.domain.exhibition.hours.PlaceKey;

/**
 * 테스트 픽스처 헬퍼 — 전시장 분리(exhibition_place N:1) 이후 테스트가 전시를 만들 때마다 전시장부터 resolve-or-create해야 하는
 * 반복을 줄인다. 도메인 규칙(정규화 이름 upsert)을 프로덕션과 동일하게 재사용한다.
 */
public final class ExhibitionTestFactory {

	private ExhibitionTestFactory() {
	}

	/** 전시장 resolve-or-create 후 id 반환(정규화 이름 기준). */
	public static Long placeId(ExhibitionPlaceRepository repository, String name, ExhibitionRegion region) {
		return repository.findByPlaceKey(PlaceKey.of(name))
				.orElseGet(() -> repository.save(ExhibitionPlace.createFromList(name, region, null, null, null)))
				.getId();
	}

	/** 지정 전시장에 소속된 CATALOG 전시 하나를 만든다(영속화는 호출부). 지역 미지정. */
	public static Exhibition catalog(Long placeId, String externalId, String title, LocalDate startDate,
			LocalDate endDate, ExhibitionCategory category) {
		return catalog(placeId, null, externalId, title, startDate, endDate, category);
	}

	/**
	 * 지역까지 지정하는 변형. 지역 필터를 검증하는 테스트는 <b>반드시 이쪽</b>을 써야 한다 — V49 이후 지역은
	 * 전시장 조인이 아니라 {@code exhibitions.region} 복제본에서 오므로, 전시장에만 지역을 넣으면
	 * 필터가 0건을 돌려주고 "count도 0, 목록도 0"이라 대조 테스트가 <b>공허하게 통과</b>한다.
	 */
	public static Exhibition catalog(Long placeId, ExhibitionRegion region, String externalId, String title,
			LocalDate startDate, LocalDate endDate, ExhibitionCategory category) {
		return Exhibition.createCatalog(externalId, title, placeId, region, startDate, endDate, category, null, null,
				"기관");
	}
}
