package modi.backend.ingestionv2.common.inbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Inbox 조건부 상태 전이 SQL. */
public interface InboxJpaRepository extends JpaRepository<InboxMessage, Long> {

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			INSERT IGNORE INTO ingestion_inbox
				(subscriber_key, event_id, status, claim_token, started_at, lease_until)
			VALUES (:subscriberKey, :eventId, 'PROCESSING', :claimToken, :startedAt, :leaseUntil)
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("subscriberKey") String subscriberKey,
			@Param("eventId") String eventId,
			@Param("claimToken") String claimToken,
			@Param("startedAt") LocalDateTime startedAt,
			@Param("leaseUntil") LocalDateTime leaseUntil);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE ingestion_inbox
			SET status = 'PROCESSING', claim_token = :claimToken, started_at = :startedAt,
				lease_until = :leaseUntil, completed_at = NULL, last_error = NULL
			WHERE subscriber_key = :subscriberKey AND event_id = :eventId
				AND (status = 'FAILED' OR (status = 'PROCESSING' AND lease_until <= :startedAt))
			""", nativeQuery = true)
	int reclaimIfAvailable(
			@Param("subscriberKey") String subscriberKey,
			@Param("eventId") String eventId,
			@Param("claimToken") String claimToken,
			@Param("startedAt") LocalDateTime startedAt,
			@Param("leaseUntil") LocalDateTime leaseUntil);

	@Query(value = """
			SELECT status FROM ingestion_inbox
			WHERE subscriber_key = :subscriberKey AND event_id = :eventId
			""", nativeQuery = true)
	Optional<String> findStatus(
			@Param("subscriberKey") String subscriberKey,
			@Param("eventId") String eventId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE ingestion_inbox
			SET status = :terminalStatus, lease_until = NULL, completed_at = :completedAt, last_error = NULL
			WHERE subscriber_key = :subscriberKey AND event_id = :eventId
				AND status = 'PROCESSING' AND claim_token = :claimToken
			""", nativeQuery = true)
	int finish(
			@Param("subscriberKey") String subscriberKey,
			@Param("eventId") String eventId,
			@Param("claimToken") String claimToken,
			@Param("terminalStatus") String terminalStatus,
			@Param("completedAt") LocalDateTime completedAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE ingestion_inbox
			SET status = 'FAILED', lease_until = NULL, completed_at = NULL, last_error = :lastError
			WHERE subscriber_key = :subscriberKey AND event_id = :eventId
				AND status = 'PROCESSING' AND claim_token = :claimToken
			""", nativeQuery = true)
	int fail(
			@Param("subscriberKey") String subscriberKey,
			@Param("eventId") String eventId,
			@Param("claimToken") String claimToken,
			@Param("lastError") String lastError);

	List<InboxMessage> findByStatusInAndCompletedAtBeforeOrderByCompletedAtAscIdAsc(
			Collection<InboxStatus> statuses, LocalDateTime threshold, Pageable pageable);
}
