package modi.backend.application.exhibition;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;

/**
 * {@link PlaceHoursGateway} 구현 — 영업시간 정준층({@code place_hours})의 대상 선별·반영을 전시장 애그리거트
 * 루트 경유로 수행한다(구 ExhibitionSyncFacade의 hours 메서드, ADR-12). 벤더 원본 적재는 수집 쪽 소관.
 */
@Service
@RequiredArgsConstructor
public class PlaceHoursGatewayFacade implements PlaceHoursGateway {

	private static final Logger log = LoggerFactory.getLogger(PlaceHoursGatewayFacade.class);

	private final ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Override
	@Transactional(readOnly = true)
	public Optional<PlaceHoursTarget> resolvePlaceHoursTarget(String placeKey) {
		return exhibitionPlaceRepository.findByPlaceKey(placeKey)
				.filter(place -> place.getAddress() != null && !place.getAddress().isBlank())
				.map(place -> new PlaceHoursTarget(place.getId(), place.getName(), place.getAddress()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<PlaceHoursTarget> findPlacesNeedingHours(LocalDateTime staleBefore, int maxVenues) {
		return exhibitionPlaceRepository.findPlacesNeedingHours(staleBefore, Math.max(1, maxVenues)).stream()
				.map(p -> new PlaceHoursTarget(p.getId(), p.getName(), p.getAddress()))
				.toList();
	}

	@Override
	@Transactional
	public void applyHours(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status, PlaceHoursVendor vendor,
			LocalDateTime now) {
		exhibitionPlaceRepository.applyHours(exhibitionPlaceId, formatted, status, vendor, now);
	}

	@Override
	@Transactional
	public void markHoursFailure(Long exhibitionPlaceId, PlaceHoursVendor vendor) {
		try {
			exhibitionPlaceRepository.markHoursFailure(exhibitionPlaceId, vendor);
		} catch (RuntimeException e) {
			log.warn("영업시간 실패 기록 실패(placeId={}, 보강은 계속): {}", exhibitionPlaceId, e.getMessage());
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<HoursSyncState> findHoursSyncState(String placeKey) {
		return exhibitionPlaceRepository.findByPlaceKey(placeKey)
				.flatMap(place -> exhibitionPlaceRepository.findHours(place.getId()))
				.map(hours -> new HoursSyncState(hours.getSyncedAt()));
	}
}
