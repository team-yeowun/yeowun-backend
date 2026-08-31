package modi.backend.ingestionv2.enrich.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import modi.backend.ingestionv2.enrich.domain.Enrichment;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStatus;
import modi.backend.ingestionv2.enrich.domain.StepStatus;

/** 보강 애그리거트 Spring Data 리포지토리. */
public interface EnrichmentJpaRepository extends JpaRepository<Enrichment, Long> {

	Optional<Enrichment> findByVendorKey(String vendorKey);

	boolean existsByVendorKey(String vendorKey);

	/** 완료 판정을 직렬화하기 위한 잠금 조회. 잠금 범위는 그 전시의 루트 행 하나다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Enrichment e where e.vendorKey = :vendorKey")
	Optional<Enrichment> findForUpdateByVendorKey(@Param("vendorKey") String vendorKey);

	/**
	 * 관리자 실패 목록.
	 *
	 * <ul>
	 *   <li>하위 셋을 조인 페치 - 없으면 목록 스무 건에 하위 조회가 예순 번 따라붙는다</li>
	 *   <li>일대일이라 페이지네이션과 함께 써도 결과 행 수가 늘지 않는다</li>
	 *   <li>정렬이 식별자 역순인 것은 루트에 갱신 시각 컬럼이 없기 때문 - 증가하는 값이라 생성 순서와 같다</li>
	 * </ul>
	 */
	@Query("""
			select e from Enrichment e
			join fetch e.detail
			join fetch e.genre
			join fetch e.hours
			where e.status = :status
			order by e.id desc
			""")
	List<Enrichment> findByStatusWithSteps(@Param("status") EnrichmentStatus status, Pageable pageable);

	long countByStatus(EnrichmentStatus status);

	@Query("select count(e) from Enrichment e where e.status = :status and e.detail.status = :stepStatus")
	long countDetailAtStatus(@Param("status") EnrichmentStatus status, @Param("stepStatus") StepStatus stepStatus);

	@Query("select count(e) from Enrichment e where e.status = :status and e.genre.status = :stepStatus")
	long countGenreAtStatus(@Param("status") EnrichmentStatus status, @Param("stepStatus") StepStatus stepStatus);

	@Query("select count(e) from Enrichment e where e.status = :status and e.hours.status = :stepStatus")
	long countHoursAtStatus(@Param("status") EnrichmentStatus status, @Param("stepStatus") StepStatus stepStatus);
}
