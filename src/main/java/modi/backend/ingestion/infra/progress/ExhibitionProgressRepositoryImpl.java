package modi.backend.ingestion.infra.progress;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.domain.progress.ExhibitionProgress;
import modi.backend.ingestion.domain.progress.ExhibitionProgressRepository;

/** 진행 상태 어댑터(구 ExhibitionDraftRepositoryImpl) — 파이프라인 상태 테이블이라 soft-delete 필터가 없다. */
@Repository
@RequiredArgsConstructor
public class ExhibitionProgressRepositoryImpl implements ExhibitionProgressRepository {

	private final ExhibitionProgressJpaRepository jpaRepository;

	@Override
	public ExhibitionProgress save(ExhibitionProgress progress) {
		return jpaRepository.save(progress);
	}

	@Override
	public Optional<ExhibitionProgress> findByExternalId(String externalId) {
		return jpaRepository.findByExternalId(externalId);
	}

	@Override
	public List<ExhibitionProgress> findAllByPlaceKey(String placeKey) {
		return jpaRepository.findAllByPlaceKey(placeKey);
	}
}
