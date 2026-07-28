package modi.backend.application.exhibition;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.application.exhibition.contract.PlaceRegistrar;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.PlaceKey;

/**
 * 수집 슬라이스가 <b>전시장 애그리거트</b>에 닿는 통로(ADR-12). {@link PlaceRegistrar}(신원 확정)와
 * {@link PlaceHoursGateway}(영업시간 반영)를 함께 구현한다 — 둘 다 {@link ExhibitionPlaceRepository}
 * 루트 하나를 감싸는 얇은 위임이라 같은 자리에 둔다.
 *
 * <p><b>계약은 둘로 남긴다</b>: 소비자는 필요한 쪽만 주입받아 쓴다. 구현이 하나라는 사실은 소비자가 알 필요가 없고,
 * 나중에 한쪽의 조율이 복잡해지면 그 계약만 떼어내면 된다.
 */
@Service
@RequiredArgsConstructor
public class PlaceGatewayFacade implements PlaceRegistrar, PlaceHoursGateway {

	private final ExhibitionPlaceRepository exhibitionPlaceRepository;

	/**
	 * 전시장 resolve-or-create를 애그리거트 루트 경유로 수행한다. 신규 여부는 자연키 선조회로 판정한다 —
	 * 같은 place_key의 PLACE_STAGED는 아웃박스 UK(type, target_key)로 한 건뿐이라 이 판정이 경합에 노출되지 않고,
	 * 설령 승격의 resolve-or-create가 먼저 만들었더라도 "기존"으로 판정되어 구글 중복 호출이 나지 않는다
	 * (장소당 1콜의 마지막 가드).
	 */
	@Override
	@Transactional
	public Resolved resolveOrCreate(String placeName, ExhibitionRegion region, String sigungu, Double gpsX,
			Double gpsY) {
		boolean existed = exhibitionPlaceRepository.findByPlaceKey(PlaceKey.of(placeName)).isPresent();
		ExhibitionPlace place = exhibitionPlaceRepository.resolveOrCreate(placeName, region, sigungu, gpsX, gpsY);
		return new Resolved(place.getId(), place.getPlaceKey(), !existed);
	}

	/**
	 * 영업시간 정준층({@code place_hours}) 반영을 전시장 애그리거트 루트 경유로 수행한다.
	 * 재검증 폐기(설계 D4)로 선별·가드·실패 마킹이 소멸해 반영 하나만 남았다.
	 */
	@Override
	@Transactional
	public void applyHours(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status, PlaceHoursVendor vendor,
			LocalDateTime now) {
		exhibitionPlaceRepository.applyHours(exhibitionPlaceId, formatted, status, vendor, now);
	}
}
