package modi.backend.ingestionv2.enrich.domain;

import java.util.Optional;

/**
 * 보강 애그리거트 포트.
 *
 * <ul>
 *   <li>findForUpdate는 완료 판정을 직렬화하기 위한 잠금 조회</li>
 *   <li>잠금 여부를 포트 메서드 이름으로 드러냄. 호출부가 무엇을 기대하는지 코드에 남기기 위함</li>
 * </ul>
 */
public interface EnrichmentRepository {

	Enrichment save(Enrichment enrichment);

	Optional<Enrichment> findByVendorKey(String vendorKey);

	Optional<Enrichment> findForUpdate(String vendorKey);

	boolean existsByVendorKey(String vendorKey);
}
