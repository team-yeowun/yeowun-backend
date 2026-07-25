package modi.backend.application.exhibition;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;

/**
 * {@link PlaceHoursGateway} 구현 — 영업시간 정준층({@code place_hours}) 반영을 전시장 애그리거트 루트 경유로
 * 수행한다(ADR-12). 재검증 폐기(설계 D4)로 선별·가드·실패 마킹이 소멸해 반영 하나만 남았다.
 */
@Service
@RequiredArgsConstructor
public class PlaceHoursGatewayFacade implements PlaceHoursGateway {

	private final ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Override
	@Transactional
	public void applyHours(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status, PlaceHoursVendor vendor,
			LocalDateTime now) {
		exhibitionPlaceRepository.applyHours(exhibitionPlaceId, formatted, status, vendor, now);
	}
}
