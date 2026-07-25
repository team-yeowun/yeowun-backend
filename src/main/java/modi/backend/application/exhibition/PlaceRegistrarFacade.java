package modi.backend.application.exhibition;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceRegistrar;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.hours.PlaceKey;

/**
 * {@link PlaceRegistrar} 구현 — 전시장 resolve-or-create를 애그리거트 루트({@link ExhibitionPlaceRepository})
 * 경유로 수행한다. 신규 여부는 자연키 선조회로 판정한다 — 같은 place_key의 PLACE_STAGED는 아웃박스
 * UK(type, target_key)로 한 건뿐이라 이 판정이 경합에 노출되지 않고, 설령 승격의 resolve-or-create가
 * 먼저 만들었더라도 "기존"으로 판정되어 구글 중복 호출이 나지 않는다(장소당 1콜의 마지막 가드).
 */
@Service
@RequiredArgsConstructor
public class PlaceRegistrarFacade implements PlaceRegistrar {

	private final ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Override
	@Transactional
	public Resolved resolveOrCreate(String placeName, ExhibitionRegion region, String sigungu, Double gpsX,
			Double gpsY) {
		boolean existed = exhibitionPlaceRepository.findByPlaceKey(PlaceKey.of(placeName)).isPresent();
		ExhibitionPlace place = exhibitionPlaceRepository.resolveOrCreate(placeName, region, sigungu, gpsX, gpsY);
		return new Resolved(place.getId(), place.getPlaceKey(), !existed);
	}
}
