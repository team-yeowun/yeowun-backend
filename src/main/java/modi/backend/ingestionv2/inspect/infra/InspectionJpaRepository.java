package modi.backend.ingestionv2.inspect.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.ingestionv2.inspect.domain.Inspection;
import modi.backend.ingestionv2.inspect.domain.InspectionStatus;

/**
 * 점검 Spring Data 리포지토리.
 *
 * <ul>
 *   <li>상태로 먼저 좁힘 - 복합 인덱스 (status, inspected_at)를 타는 조건</li>
 *   <li>사유 조건은 콤마 문자열 안을 찾는 함수 호출 - 인덱스를 타지 않으므로 반려 구간 안에서만 수행</li>
 *   <li>정렬에 id를 덧붙임 - 같은 시각의 행 사이 순서를 고정해 페이지가 흔들리지 않게 함</li>
 * </ul>
 */
public interface InspectionJpaRepository extends JpaRepository<Inspection, Long> {

	Optional<Inspection> findByVendorKey(String vendorKey);

	@Query("""
			select i from Inspection i
			where i.status = :status
			  and (:reason is null or function('find_in_set', :reason, i.rejectReasonCodes) > 0)
			order by i.inspectedAt desc, i.id desc
			""")
	List<Inspection> findByStatusAndReason(@Param("status") InspectionStatus status,
			@Param("reason") String reason, Pageable pageable);

	@Query("""
			select count(i) from Inspection i
			where i.status = :status
			  and (:reason is null or function('find_in_set', :reason, i.rejectReasonCodes) > 0)
			""")
	long countByStatusAndReason(@Param("status") InspectionStatus status, @Param("reason") String reason);
}
