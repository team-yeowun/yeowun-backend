package modi.backend.infra.exhibition.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

	/** 단건 조회(살아있는 행만). 필터를 앱이 아니라 WHERE에 둬 인덱스에 태운다. */
	Optional<Exhibition> findByIdAndDeletedAtIsNull(Long id);

	/**
	 * 주어진 id들 중 종료일이 {@code [from, to]} 구간인 살아있는 전시(북마크 종료임박 알림 선별용).
	 * 예전엔 북마크 전량을 읽어 앱에서 D-day를 판정했다 — 선별을 WHERE로 내려 읽는 행 수를 대상만큼으로 줄인다.
	 */
	List<Exhibition> findByIdInAndDeletedAtIsNullAndEndDateBetween(java.util.Collection<Long> ids,
			java.time.LocalDate from, java.time.LocalDate to);

	/**
	 * 홈 배너용 — 진행 중(startDate ≤ onDate ≤ endDate)인 CATALOG를 조회수 내림차순으로 페이지 크기만큼 조회(살아있는 행만).
	 * 진행 중 조건은 두 날짜 파라미터에 동일한 오늘 값을 넘겨 표현한다.
	 *
	 * <p><b>날짜 미상(센티널)은 배너에서 뺀다</b> — 파생 쿼리로 두면 표현할 수 없어 JPQL로 내렸다.
	 * V47 이전에는 날짜가 {@code NULL}이라 3치 논리로 조용히 빠졌는데, 센티널로 바뀌면서
	 * {@code 1000-01-01 ≤ 오늘 ≤ 9999-12-31}이 참이 되어 <b>미상 전시가 배너에 새로 들어왔다</b>.
	 * 목록(Specification)은 미상을 포함하지만 배너는 포함하지 않는 것이 원래 동작이고, 그 동작을 유지한다
	 * — 언제 시작하고 끝나는지 모르는 전시를 홈 대문에 올리지 않는다.
	 * 시작·종료 중 <b>하나라도</b> 미상이면 뺀다(= NULL이던 시절의 3치 논리와 같은 결과).
	 */
	@Query("select e from Exhibition e where e.type = :type and e.deletedAt is null "
			+ "and e.startDate <= :onDate and e.endDate >= :onDate "
			+ "and e.startDate <> :startUnknown and e.endDate <> :endUnknown "
			+ "order by e.ourViewCount desc")
	List<Exhibition> findOngoingCatalogTopByViews(@Param("type") ExhibitionType type,
			@Param("onDate") java.time.LocalDate onDate,
			@Param("startUnknown") java.time.LocalDate startUnknown,
			@Param("endUnknown") java.time.LocalDate endUnknown, Pageable pageable);

	// ── 관리자 콘솔 전용 ───────────────────────────────

	/** 전체 전시 수(살아있는). */
	long countByDeletedAtIsNull();

	/** 타입별 전시 수(CATALOG/CUSTOM 구분 — 대시보드용). */
	long countByTypeAndDeletedAtIsNull(ExhibitionType type);

	/** 여러 전시를 ID로 조회(사용자 상세의 북마크/전시활동 제목 표시용, 살아있는 행만). */
	List<Exhibition> findByIdInAndDeletedAtIsNull(java.util.Collection<Long> ids);

	/** 주어진 id들 중 살아있는 전시 수(관심 전시 목록의 totalCount — 삭제된 전시를 뺀 정확한 값). */
	long countByIdInAndDeletedAtIsNull(java.util.Collection<Long> ids);

	/**
	 * 주어진 id들을 종료일 오름차순(nulls last)·id 오름차순으로 한 페이지 조회한다(관심 전시 "종료 임박순").
	 * 정렬 키가 전시 컬럼이라 북마크 테이블만으로는 못 자른다 — 정렬·LIMIT을 DB에 맡겨 반환 행만 앱으로 올린다.
	 *
	 * <p><b>V47 이후</b>: {@code end_date}가 NOT NULL이 되어 아래 세 쿼리의 {@code is null} 분기는 어느 행에도
	 * 걸리지 않는다. 종료일 미상은 센티널({@code 9999-12-31})이라 nulls last를 흉내 내던 {@code case when}이
	 * 없어도 자연히 맨 뒤에 선다. 결과가 바뀌지 않으므로 이번 단계에서는 표현을 그대로 둔다
	 * (이 경로는 이번 실험의 측정 대상이 아니다 — 손대면 검증만 늘어난다).
	 */
	@Query("select e from Exhibition e where e.id in :ids and e.deletedAt is null "
			+ "order by case when e.endDate is null then 1 else 0 end, e.endDate asc, e.id asc")
	List<Exhibition> findActiveByIdsOrderByEndDate(@Param("ids") java.util.Collection<Long> ids, Pageable pageable);

	/** 위와 같은 정렬의 다음 페이지 — 커서 행({@code endDate}, {@code lastId})보다 뒤만. */
	@Query("select e from Exhibition e where e.id in :ids and e.deletedAt is null "
			+ "and (e.endDate > :endDate or e.endDate is null or (e.endDate = :endDate and e.id > :lastId)) "
			+ "order by case when e.endDate is null then 1 else 0 end, e.endDate asc, e.id asc")
	List<Exhibition> findActiveByIdsOrderByEndDateAfter(@Param("ids") java.util.Collection<Long> ids,
			@Param("endDate") java.time.LocalDate endDate, @Param("lastId") Long lastId, Pageable pageable);

	/**
	 * 커서가 이미 nulls last 블록(종료일 미상)에 있을 때의 다음 페이지.
	 * V47 이후 호출부({@code BookmarkFacade})가 커서 키를 센티널로 채워 넘기므로 <b>더 이상 도달하지 않는다</b>.
	 */
	@Query("select e from Exhibition e where e.id in :ids and e.deletedAt is null "
			+ "and e.endDate is null and e.id > :lastId order by e.id asc")
	List<Exhibition> findActiveByIdsWithoutEndDateAfter(@Param("ids") java.util.Collection<Long> ids,
			@Param("lastId") Long lastId, Pageable pageable);
}
