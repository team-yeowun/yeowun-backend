package modi.backend.ingestionv2.common.outbox;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 아웃박스 스프링 데이터 리포지토리.
 *
 * <ul>
 *   <li>선점만 네이티브 - SKIP LOCKED는 JPQL로 표현할 수 없음</li>
 *   <li>정렬 기준은 전부 created_at - (status, created_at) 인덱스가 정렬까지 대신한다</li>
 * </ul>
 */
public interface OutboxJpaRepository extends JpaRepository<Outbox, Long> {

	@Query(value = """
			SELECT * FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY created_at, id
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Outbox> claimPending(@Param("limit") int limit);

	List<Outbox> findByStatusOrderByCreatedAtAscIdAsc(OutboxStatus status, Pageable pageable);

	long countByStatus(OutboxStatus status);

	List<Outbox> findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(OutboxStatus status,
			LocalDateTime threshold, Pageable pageable);
}
