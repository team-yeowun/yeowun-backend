package modi.backend.ingestionv2.enrich.domain.detail;

import java.util.Optional;

/** 상세 원장 포트. */
public interface DetailLedgerRepository {

	CultureDetailSnapshot save(CultureDetailSnapshot snapshot);

	Optional<CultureDetailSnapshot> findByVendorKey(String vendorKey);

	boolean existsByVendorKey(String vendorKey);
}
