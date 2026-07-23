package modi.backend.ingestion.infra.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.IngestionEventType;

public interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessage, Long> {

	Optional<OutboxMessage> findByMessageTypeAndTargetKey(IngestionEventType messageType, String targetKey);

	/**
	 * 선별 쿼리 — 인덱스 {@code (status, next_attempt_at)}를 타 도래 순으로 집는다.
	 * limit은 {@link Pageable}로 준다(파생 top-N은 상수만 되므로 값 제어를 위해 @Query + Pageable).
	 */
	@Query("select j from OutboxMessage j "
			+ "where j.messageType = :messageType and j.status in :statuses and j.nextAttemptAt <= :now "
			+ "order by j.nextAttemptAt asc")
	List<OutboxMessage> findDue(@Param("messageType") IngestionEventType messageType,
			@Param("statuses") Collection<OutboxMessageStatus> statuses,
			@Param("now") LocalDateTime now, Pageable pageable);

	long countByStatus(OutboxMessageStatus status);
}
