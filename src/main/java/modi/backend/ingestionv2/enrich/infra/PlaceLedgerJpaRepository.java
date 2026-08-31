package modi.backend.ingestionv2.enrich.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestionv2.enrich.domain.hours.GooglePlaceSnapshot;

/** 개장 시간 원장 Spring Data 리포지토리. */
public interface PlaceLedgerJpaRepository extends JpaRepository<GooglePlaceSnapshot, Long> {

	Optional<GooglePlaceSnapshot> findByVendorKey(String vendorKey);

	boolean existsByVendorKey(String vendorKey);
}
