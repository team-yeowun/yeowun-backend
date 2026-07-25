package modi.backend.infra.exhibition.catalog;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.PlaceKey;
import modi.backend.infra.exhibition.hours.PlaceHoursJpaRepository;

/**
 * 전시장 애그리거트 어댑터(DIP) — 루트(exhibition_place)와 영업시간 정준행(place_hours 1:1)의
 * JpaRepository 2개를 이 안에서 조율한다. 자연키(정규화 이름) upsert와 영업시간 upsert 분기는
 * 전부 여기로 모인다(상태 변경은 entity 행위 메서드로). 조회는 살아있는 행만 본다.
 */
@Repository
@RequiredArgsConstructor
public class ExhibitionPlaceRepositoryImpl implements ExhibitionPlaceRepository {

	private final ExhibitionPlaceJpaRepository jpaRepository;
	private final PlaceHoursJpaRepository placeHoursJpaRepository;

	@Override
	public ExhibitionPlace save(ExhibitionPlace place) {
		return jpaRepository.save(place);
	}

	@Override
	public Optional<ExhibitionPlace> findById(Long id) {
		return jpaRepository.findById(id).filter(p -> p.getDeletedAt() == null);
	}

	@Override
	public Optional<ExhibitionPlace> findByPlaceKey(String placeKey) {
		if (placeKey == null) {
			return Optional.empty();
		}
		return jpaRepository.findByPlaceKeyAndDeletedAtIsNull(placeKey);
	}

	@Override
	public ExhibitionPlace resolveOrCreate(String name, ExhibitionRegion region, String sigungu, Double gpsX,
			Double gpsY) {
		String placeKey = PlaceKey.of(name);
		return findByPlaceKey(placeKey)
				.map(existing -> {
					existing.enrichIdentity(region, sigungu, gpsX, gpsY);
					return jpaRepository.save(existing);
				})
				.orElseGet(() -> jpaRepository.save(ExhibitionPlace.createFromList(name, region, sigungu, gpsX, gpsY)));
	}

	@Override
	public List<ExhibitionPlace> findAllByIds(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return jpaRepository.findAllById(ids).stream()
				.filter(p -> p.getDeletedAt() == null)
				.toList();
	}

	// ── 영업시간 정준행(1:1) ─────────────────────────────────────────────────────

	@Override
	public Optional<PlaceHours> findHours(Long exhibitionPlaceId) {
		if (exhibitionPlaceId == null) {
			return Optional.empty();
		}
		return placeHoursJpaRepository.findByExhibitionPlaceId(exhibitionPlaceId);
	}

	@Override
	public PlaceHours saveHours(PlaceHours placeHours) {
		return placeHoursJpaRepository.save(placeHours);
	}

	@Override
	public void applyHours(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status, PlaceHoursVendor vendor,
			LocalDateTime now) {
		if (exhibitionPlaceId == null) {
			return;
		}
		placeHoursJpaRepository.findByExhibitionPlaceId(exhibitionPlaceId)
				.ifPresentOrElse(row -> {
					row.refresh(formatted, status, vendor, now);
					placeHoursJpaRepository.save(row);
				}, () -> placeHoursJpaRepository.save(
						PlaceHours.first(exhibitionPlaceId, formatted, status, vendor, now)));
	}
}
