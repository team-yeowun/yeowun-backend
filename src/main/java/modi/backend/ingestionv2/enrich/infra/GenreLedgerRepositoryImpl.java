package modi.backend.ingestionv2.enrich.infra;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.genre.GenreLedgerRepository;
import modi.backend.ingestionv2.enrich.domain.genre.GenreSnapshot;

/** 장르 원장 포트 어댑터. */
@Repository
@RequiredArgsConstructor
public class GenreLedgerRepositoryImpl implements GenreLedgerRepository {

	private final GenreLedgerJpaRepository jpaRepository;

	@Override
	public GenreSnapshot save(GenreSnapshot snapshot) {
		return jpaRepository.save(snapshot);
	}

	@Override
	public Optional<GenreSnapshot> findByVendorKey(String vendorKey) {
		return jpaRepository.findByVendorKey(vendorKey);
	}

	@Override
	public boolean existsByVendorKey(String vendorKey) {
		return jpaRepository.existsByVendorKey(vendorKey);
	}
}
