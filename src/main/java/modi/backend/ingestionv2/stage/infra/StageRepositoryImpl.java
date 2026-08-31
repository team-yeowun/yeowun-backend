package modi.backend.ingestionv2.stage.infra;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.stage.domain.StageRepository;
import modi.backend.ingestionv2.stage.domain.Staging;

/** 포트 어댑터. 영속화 기술을 도메인 밖에 가둔다. */
@Repository
@RequiredArgsConstructor
public class StageRepositoryImpl implements StageRepository {

	private final StagingJpaRepository jpaRepository;

	@Override
	public Optional<Staging> findByVendorKey(String vendorKey) {
		return jpaRepository.findByVendorKey(vendorKey);
	}

	@Override
	public Optional<Staging> findByVendorKeyForUpdate(String vendorKey) {
		return jpaRepository.findByVendorKeyForUpdate(vendorKey);
	}

	@Override
	public Staging save(Staging staging) {
		return jpaRepository.save(staging);
	}
}
