package modi.backend.ingestionv2.collect.infra;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.collect.domain.CollectBatchClaim;
import modi.backend.ingestionv2.collect.domain.CollectRepository;
import modi.backend.ingestionv2.collect.domain.CollectedExhibition;
import modi.backend.ingestionv2.collect.domain.CultureListSnapshot;

/**
 * 수집 저장 포트 어댑터.
 *
 * <ul>
 *   <li>@Transactional 없음 (호출자인 CollectService의 트랜잭션에 합류)</li>
	 *   <li>회차 실행권의 생성·재선점·종료는 조건부 SQL 한 문장으로 상태를 전이</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class CollectRepositoryImpl implements CollectRepository {

	private final CollectBatchMarkJpaRepository batchMarkJpaRepository;
	private final CollectedExhibitionJpaRepository collectedExhibitionJpaRepository;
	private final ListLedgerJpaRepository snapshotJpaRepository;

	@Override
	public Optional<CollectBatchClaim> claimBatch(
			LocalDate batchDate, LocalDateTime claimedAt, LocalDateTime leaseUntil, String claimToken) {
		int inserted = batchMarkJpaRepository.insertIfAbsent(batchDate, claimedAt, leaseUntil, claimToken);
		if (inserted == 1) {
			return Optional.of(new CollectBatchClaim(batchDate, claimToken));
		}
		int reclaimed = batchMarkJpaRepository.reclaimIfAvailable(batchDate, claimedAt, leaseUntil, claimToken);
		return reclaimed == 1
				? Optional.of(new CollectBatchClaim(batchDate, claimToken))
				: Optional.empty();
	}

	@Override
	public boolean completeBatch(CollectBatchClaim claim, LocalDateTime completedAt) {
		return batchMarkJpaRepository.complete(claim.batchDate(), claim.token(), completedAt) == 1;
	}

	@Override
	public boolean failBatch(CollectBatchClaim claim, String lastError) {
		return batchMarkJpaRepository.fail(claim.batchDate(), claim.token(), lastError) == 1;
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
