package modi.backend.ingestionv2.enrich.infra;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.detail.CultureDetailSnapshot;
import modi.backend.ingestionv2.enrich.domain.detail.DetailLedgerRepository;

/** 상세 원장 포트 어댑터. 트랜잭션 경계는 하위 서비스가 소유한다. */
@Repository
@RequiredArgsConstructor
public class DetailLedgerRepositoryImpl implements DetailLedgerRepository {

	private final DetailLedgerJpaRepository jpaRepository;

	@Override
	public CultureDetailSnapshot save(CultureDetailSnapshot snapshot) {
		return jpaRepository.save(snapshot);
	}

	@Override
	public Optional<CultureDetailSnapshot> findByVendorKey(String vendorKey) {
		return jpaRepository.findByVendorKey(vendorKey);
	}

	@Override
	public boolean existsByVendorKey(String vendorKey) {
		return jpaRepository.existsByVendorKey(vendorKey);
	}
}
