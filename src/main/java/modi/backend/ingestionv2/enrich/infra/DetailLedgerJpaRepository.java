package modi.backend.ingestionv2.enrich.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestionv2.enrich.domain.detail.CultureDetailSnapshot;

/** 상세 원장 Spring Data 리포지토리. */
public interface DetailLedgerJpaRepository extends JpaRepository<CultureDetailSnapshot, Long> {

	Optional<CultureDetailSnapshot> findByVendorKey(String vendorKey);

	boolean existsByVendorKey(String vendorKey);
}
