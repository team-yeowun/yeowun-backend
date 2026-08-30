package modi.backend.ingestionv2.enrich.domain.genre;

import java.util.Optional;

/** 장르 원장 포트. */
public interface GenreLedgerRepository {

	GenreSnapshot save(GenreSnapshot snapshot);

	Optional<GenreSnapshot> findByVendorKey(String vendorKey);

	boolean existsByVendorKey(String vendorKey);
}
