package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.Optional;

/**
 * 수집 격벽의 저장 포트.
 *
 * <ul>
 *   <li>회차 실행권 · 멱등 판정 · 애그리거트 저장 · 원장 적재를 한 포트에 모음</li>
 *   <li>Spring 무의존 (구현은 infra/CollectRepositoryImpl)</li>
 * </ul>
 */
public interface CollectRepository {

	/** 새 회차를 만들거나 FAILED/만료 RUNNING을 원자적으로 재선점한다. */
	Optional<CollectBatchClaim> claimBatch(
			LocalDate batchDate, LocalDateTime claimedAt, LocalDateTime leaseUntil, String claimToken);

	/** 현재 token이 소유한 RUNNING 회차만 완료한다. */
	boolean completeBatch(CollectBatchClaim claim, LocalDateTime completedAt);

	/** 현재 token이 소유한 RUNNING 회차만 실패 처리한다. */
	boolean failBatch(CollectBatchClaim claim, String lastError);

	/** 이미 확정된 전시인지 판정한다. */
	boolean existsByVendorKey(String vendorKey);

	void save(CollectedExhibition collected);

	void saveSnapshot(CultureListSnapshot snapshot);
}
