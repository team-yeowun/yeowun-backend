package modi.backend.ingestionv2.collect.infra;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.collect.domain.CollectBatchMark;
import modi.backend.ingestionv2.collect.domain.CollectRepository;
import modi.backend.ingestionv2.collect.domain.CollectedExhibition;
import modi.backend.ingestionv2.collect.domain.CultureListSnapshot;

/**
 * 수집 저장 포트 어댑터.
 *
 * <ul>
 *   <li>@Transactional 없음 (호출자인 CollectService의 트랜잭션에 합류)</li>
 *   <li>선점만 조건부 삽입, 나머지는 평범한 저장</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class CollectRepositoryImpl implements CollectRepository {

	private final CollectBatchMarkJpaRepository batchMarkJpaRepository;
	private final CollectedExhibitionJpaRepository collectedExhibitionJpaRepository;
	private final ListLedgerJpaRepository snapshotJpaRepository;

	@Override
	public boolean claimBatchMark(CollectBatchMark mark) {
		return batchMarkJpaRepository.insertIfAbsent(mark.getBatchDate(), mark.getClaimedAt()) == 1;
	}

	@Override
	public boolean existsByVendorKey(String vendorKey) {
		return collectedExhibitionJpaRepository.existsByVendorKey(vendorKey);
	}

	@Override
	public void save(CollectedExhibition collected) {
		collectedExhibitionJpaRepository.save(collected);
	}

	@Override
	public void saveSnapshot(CultureListSnapshot snapshot) {
		snapshotJpaRepository.save(snapshot);
	}
}
