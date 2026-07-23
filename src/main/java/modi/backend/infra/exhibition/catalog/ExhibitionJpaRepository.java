package modi.backend.infra.exhibition.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionType;

/**
 * Spring Data JPA. 동적 필터(keyword·date·region·category·CUSTOM 노출)는
 * {@link JpaSpecificationExecutor}로 조합한다(프로젝트에 QueryDSL 미도입 → Specification 사용).
 */
public interface ExhibitionJpaRepository
		extends JpaRepository<Exhibition, Long>, JpaSpecificationExecutor<Exhibition> {

	/** soft delete된 행은 제외하고 원천 식별자로 조회(동기화 upsert용). */
	Optional<Exhibition> findByExternalIdAndDeletedAtIsNull(String externalId);

	/**
	 * 홈 배너용 — 진행 중(startDate ≤ onDate ≤ endDate)인 CATALOG를 조회수 내림차순으로 페이지 크기만큼 조회(살아있는 행만).
	 * 진행 중 조건은 두 날짜 파라미터에 동일한 오늘 값을 넘겨 표현한다.
	 */
	List<Exhibition> findByTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNullOrderByOurViewCountDesc(
			ExhibitionType type, java.time.LocalDate startOnOrBefore, java.time.LocalDate endOnOrAfter,
			Pageable pageable);

	// ── 관리자 콘솔 전용 ───────────────────────────────

	/** 전체 전시 수(살아있는). */
	long countByDeletedAtIsNull();

	/** 타입별 전시 수(CATALOG/CUSTOM 구분 — 대시보드용). */
	long countByTypeAndDeletedAtIsNull(ExhibitionType type);

	/** 여러 전시를 ID로 조회(사용자 상세의 북마크/전시활동 제목 표시용, 살아있는 행만). */
	List<Exhibition> findByIdInAndDeletedAtIsNull(java.util.Collection<Long> ids);
}
