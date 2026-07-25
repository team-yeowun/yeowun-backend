package modi.backend.domain.exhibition.catalog;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;

/**
 * 전시장 애그리거트의 쓰기 진입점(Spring 무의존, 구현은 infra — DIP).
 *
 * <p><b>애그리거트 경계</b>: 루트 {@link ExhibitionPlace} 아래에 영업시간 정준행({@link PlaceHours}, 1:1)이 속한다 —
 * 영업시간은 전시가 아니라 전시장의 속성이고(ADR-05·06), 장소당 1행이 곧 유료 조회 1회다.
 * 전시({@link Exhibition})는 이 애그리거트를 id로만 참조한다(N:1 공유 — bookmark·admin도 같은 경계로 접근).
 */
public interface ExhibitionPlaceRepository {

	ExhibitionPlace save(ExhibitionPlace place);

	Optional<ExhibitionPlace> findById(Long id);

	/** 자연키(정규화 이름)로 조회. */
	Optional<ExhibitionPlace> findByPlaceKey(String placeKey);

	/**
	 * resolve-or-create — 자연키(정규화 이름, ADR-07) upsert. 기존 행이면 비어 있던 신원 필드(지역·시군구·좌표)만
	 * 보강하고, 없으면 새로 만든다. 이름이 없으면 장소 미상 센티널로 수렴한다({@code exhibition_place_id NOT NULL} 지탱).
	 */
	ExhibitionPlace resolveOrCreate(String name, ExhibitionRegion region, String sigungu, Double gpsX, Double gpsY);

	/** 여러 전시장을 id로 일괄 조회(목록·상세의 장소 필드 조립, N+1 방지). 빈 입력이면 빈 목록. */
	List<ExhibitionPlace> findAllByIds(Collection<Long> ids);

	// ── 영업시간 정준행(1:1) ─────────────────────────────────────────────────────

	Optional<PlaceHours> findHours(Long exhibitionPlaceId);

	/** 이미 만든 영업시간 행의 저장(시드 등 특수 경로). 통상 경로는 {@link #applyHours}를 쓴다. */
	PlaceHours saveHours(PlaceHours placeHours);

	/**
	 * 영업시간 upsert — 없으면 생성, 있으면 갱신(영업시간은 바뀌는 값이라 덮어쓰기가 정상).
	 * {@code now}는 성공 확인 시각(재검증 간격 판정 기준).
	 */
	void applyHours(Long exhibitionPlaceId, String formatted, PlaceHoursStatus status, PlaceHoursVendor vendor,
			LocalDateTime now);
}
