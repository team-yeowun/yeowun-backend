package modi.backend.ingestionv2.collect.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestionv2.collect.domain.CollectedExhibition;

/** 수집 애그리거트 Spring Data 리포지토리. */
public interface CollectedExhibitionJpaRepository extends JpaRepository<CollectedExhibition, Long> {

	boolean existsByVendorKey(String vendorKey);
}
