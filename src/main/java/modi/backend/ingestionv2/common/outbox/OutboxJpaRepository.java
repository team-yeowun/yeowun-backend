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
 *   <li>선점만 네이티브 - SKIP LOCKED 는 JPQL 로 표현할 수 없음</li>
 *   <li>정렬 기준은 전부 created_at - (status, created_at) 인덱스가 정렬까지 대신한다</li>
 *   <li>선점 쿼리 여섯은 조건·정렬이 전부 같고 잠금 절과 LIMIT 유무만 다르다 - 부하 실험이 락 방식만 바꿔
 *       비교할 수 있으려면 나머지가 한 글자도 달라선 안 된다</li>
 *   <li>상한 없는 짝이 따로 있는 이유 - 무제한을 큰 수 바인딩으로 흉내 내면 옵티마이저가 여전히 LIMIT 를 보고
 *       계획을 세워, "상한이 없다"는 조건 자체가 재현되지 않는다</li>
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

	@Query(value = """
			SELECT * FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY created_at, id
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Outbox> claimAllPending();

	@Query(value = """
			SELECT * FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY created_at, id
			LIMIT :limit
			FOR UPDATE
			""", nativeQuery = true)
	List<Outbox> claimPendingForUpdate(@Param("limit") int limit);

	@Query(value = """
			SELECT * FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY created_at, id
			FOR UPDATE
			""", nativeQuery = true)
	List<Outbox> claimAllPendingForUpdate();

	@Query(value = """
			SELECT * FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY created_at, id
			LIMIT :limit
			""", nativeQuery = true)
	List<Outbox> selectPending(@Param("limit") int limit);

	@Query(value = """
			SELECT * FROM ingestion_outbox
			WHERE status = 'PENDING'
			ORDER BY created_at, id
			""", nativeQuery = true)
	List<Outbox> selectAllPending();

	List<Outbox> findByStatusOrderByCreatedAtAscIdAsc(OutboxStatus status, Pageable pageable);

	long countByStatus(OutboxStatus status);

	List<Outbox> findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(OutboxStatus status,
			LocalDateTime threshold, Pageable pageable);
}
