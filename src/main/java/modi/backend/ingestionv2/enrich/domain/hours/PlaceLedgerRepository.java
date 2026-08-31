package modi.backend.ingestionv2.enrich.domain.hours;

import java.util.Optional;

/** 개장 시간 원장 포트. */
public interface PlaceLedgerRepository {

	GooglePlaceSnapshot save(GooglePlaceSnapshot snapshot);

	Optional<GooglePlaceSnapshot> findByVendorKey(String vendorKey);

	boolean existsByVendorKey(String vendorKey);
}
