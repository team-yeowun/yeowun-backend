package modi.backend.ingestionv2.enrich.infra;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.Enrichment;
import modi.backend.ingestionv2.enrich.domain.EnrichmentRepository;

/** 보강 애그리거트 포트 어댑터. */
@Repository
@RequiredArgsConstructor
public class EnrichmentRepositoryImpl implements EnrichmentRepository {

	private final EnrichmentJpaRepository jpaRepository;

	@Override
	public Enrichment save(Enrichment enrichment) {
		return jpaRepository.save(enrichment);
	}

	@Override
	public Optional<Enrichment> findByVendorKey(String vendorKey) {
		return jpaRepository.findByVendorKey(vendorKey);
	}

	@Override
	public Optional<Enrichment> findForUpdate(String vendorKey) {
		return jpaRepository.findForUpdateByVendorKey(vendorKey);
	}

	@Override
	public boolean existsByVendorKey(String vendorKey) {
		return jpaRepository.existsByVendorKey(vendorKey);
	}
}
