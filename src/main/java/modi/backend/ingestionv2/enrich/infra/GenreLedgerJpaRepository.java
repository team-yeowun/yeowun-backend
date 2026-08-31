package modi.backend.ingestionv2.enrich.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import modi.backend.ingestionv2.enrich.domain.genre.GenreSnapshot;

/** 장르 원장 Spring Data 리포지토리. */
public interface GenreLedgerJpaRepository extends JpaRepository<GenreSnapshot, Long> {

	Optional<GenreSnapshot> findByVendorKey(String vendorKey);

	boolean existsByVendorKey(String vendorKey);
}
