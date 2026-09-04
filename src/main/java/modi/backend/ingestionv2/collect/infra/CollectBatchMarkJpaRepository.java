package modi.backend.ingestionv2.collect.infra;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.ingestionv2.collect.domain.CollectBatchMark;

/**
 * 회차 실행권 Spring Data 리포지토리.
 *
 * <ul>
 *   <li>INSERT IGNORE와 조건부 UPDATE로 조회 후 저장 사이의 경합 창을 없앰</li>
 *   <li>FAILED 또는 만료된 RUNNING만 새 token으로 재선점</li>
 *   <li>완료·실패 UPDATE에도 token을 걸어 오래된 실행을 차단</li>
 *   <li>INSERT IGNORE 는 MySQL 문법 - 이 결합은 이 메서드 안에 갇힌다</li>
 * </ul>
 */
public interface CollectBatchMarkJpaRepository extends JpaRepository<CollectBatchMark, Long> {

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
			value = """
					INSERT IGNORE INTO ingestion_collect_batch_mark
						(batch_date, status, claim_token, claimed_at, lease_until)
					VALUES (:batchDate, 'RUNNING', :claimToken, :claimedAt, :leaseUntil)
					""",
			nativeQuery = true)
	int insertIfAbsent(
			@Param("batchDate") LocalDate batchDate,
			@Param("claimedAt") LocalDateTime claimedAt,
			@Param("leaseUntil") LocalDateTime leaseUntil,
			@Param("claimToken") String claimToken);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
			value = """
					UPDATE ingestion_collect_batch_mark
					SET status = 'RUNNING', claim_token = :claimToken, claimed_at = :claimedAt,
						lease_until = :leaseUntil, completed_at = NULL, last_error = NULL
					WHERE batch_date = :batchDate
						AND (status = 'FAILED' OR (status = 'RUNNING' AND lease_until <= :claimedAt))
					""",
			nativeQuery = true)
	int reclaimIfAvailable(
			@Param("batchDate") LocalDate batchDate,
			@Param("claimedAt") LocalDateTime claimedAt,
			@Param("leaseUntil") LocalDateTime leaseUntil,
			@Param("claimToken") String claimToken);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
			value = """
					UPDATE ingestion_collect_batch_mark
					SET status = 'COMPLETED', lease_until = NULL, completed_at = :completedAt, last_error = NULL
					WHERE batch_date = :batchDate AND status = 'RUNNING' AND claim_token = :claimToken
					""",
			nativeQuery = true)
	int complete(
			@Param("batchDate") LocalDate batchDate,
			@Param("claimToken") String claimToken,
			@Param("completedAt") LocalDateTime completedAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
			value = """
					UPDATE ingestion_collect_batch_mark
					SET status = 'FAILED', lease_until = NULL, completed_at = NULL, last_error = :lastError
					WHERE batch_date = :batchDate AND status = 'RUNNING' AND claim_token = :claimToken
					""",
			nativeQuery = true)
	int fail(
			@Param("batchDate") LocalDate batchDate,
			@Param("claimToken") String claimToken,
			@Param("lastError") String lastError);

	Optional<CollectBatchMark> findByBatchDate(LocalDate batchDate);
}
