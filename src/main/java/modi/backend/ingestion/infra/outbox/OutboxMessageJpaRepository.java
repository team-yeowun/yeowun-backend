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

	/** SUCCEEDED 소량 배치 삭제 — MySQL DELETE LIMIT(파생쿼리 불가라 native). 호출자 tx 필수(@Modifying). */
	@org.springframework.data.jpa.repository.Modifying
	@Query(value = "delete from exhibition_outbox where status = 'SUCCEEDED' and completed_at < :cutoff limit :batchSize",
			nativeQuery = true)
	int purgeSucceededBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

	// ── 관리자 대시보드 질의(슬라이스 내부 실용 — 파사드 직사용 허용) ─────────────────

	org.springframework.data.domain.Page<OutboxMessage> findAllByStatus(OutboxMessageStatus status,
			Pageable pageable);
}
