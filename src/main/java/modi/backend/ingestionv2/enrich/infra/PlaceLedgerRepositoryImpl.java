package modi.backend.ingestionv2.enrich.infra;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.hours.GooglePlaceSnapshot;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceLedgerRepository;

/** 개장 시간 원장 포트 어댑터. */
@Repository
@RequiredArgsConstructor
public class PlaceLedgerRepositoryImpl implements PlaceLedgerRepository {

	private final PlaceLedgerJpaRepository jpaRepository;

	@Override
	public GooglePlaceSnapshot save(GooglePlaceSnapshot snapshot) {
		return jpaRepository.save(snapshot);
	}

	@Override
	public Optional<GooglePlaceSnapshot> findByVendorKey(String vendorKey) {
		return jpaRepository.findByVendorKey(vendorKey);
	}

	@Override
	public boolean existsByVendorKey(String vendorKey) {
		return jpaRepository.existsByVendorKey(vendorKey);
	}
}
