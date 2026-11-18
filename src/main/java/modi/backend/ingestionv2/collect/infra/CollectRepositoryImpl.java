package modi.backend.ingestionv2.collect.infra;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.collect.domain.CollectBatchClaim;
import modi.backend.ingestionv2.collect.domain.CollectBatchMark;
import modi.backend.ingestionv2.collect.domain.CollectRepository;
import modi.backend.ingestionv2.collect.domain.CollectedExhibition;
import modi.backend.ingestionv2.collect.domain.CultureListSnapshot;

/**
 * 수집 저장 포트 어댑터.
 *
 * <ul>
 *   <li>@Transactional 없음 (호출자인 CollectService의 트랜잭션에 합류)</li>
	 *   <li>회차 실행권의 생성·재선점·종료는 조건부 SQL 한 문장으로 상태를 전이</li>
 *   <li>선점은 잠금 없는 읽기로 갈림길을 정한 뒤 INSERT IGNORE 또는 조건부 UPDATE 중 하나만 실행한다.
 *       실패한 INSERT IGNORE 뒤에 같은 트랜잭션에서 UPDATE 로 이어가면 중복 키 검사가 남긴 공유 잠금 위에
 *       배타 잠금을 요청해, 동시 진입이 셋 이상일 때 패자끼리 데드락(1213)이 난다</li>
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
		CollectBatchClaim claim = new CollectBatchClaim(batchDate, claimToken);
		Optional<CollectBatchMark> existing = batchMarkJpaRepository.findByBatchDate(batchDate);
		if (existing.isEmpty()) {
			// 0행이면 동시 진입에서 진 것이다. 여기서 끝내고 UPDATE 로 이어가지 않는다(클래스 주석의 데드락 경로).
			int inserted = batchMarkJpaRepository.insertIfAbsent(batchDate, claimedAt, leaseUntil, claimToken);
			return inserted == 1 ? Optional.of(claim) : Optional.empty();
		}
		if (!existing.get().reclaimableAt(claimedAt)) {
			return Optional.empty();
		}
		// 읽은 뒤 다른 실행이 먼저 재선점했으면 WHERE 조건이 현재 행으로 다시 평가돼 0행으로 진다.
		int reclaimed = batchMarkJpaRepository.reclaimIfAvailable(batchDate, claimedAt, leaseUntil, claimToken);
		return reclaimed == 1 ? Optional.of(claim) : Optional.empty();
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
