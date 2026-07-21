package modi.backend.infra.remind;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.domain.record.Record;
import modi.backend.domain.remind.Remind;
import modi.backend.domain.remind.RemindAiStatus;

public interface RemindJpaRepository extends JpaRepository<Remind, Long> {

	Optional<Remind> findByIdAndDeletedAtIsNull(Long id);

	Page<Remind> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

	/**
	 * 소환 대상 후보 — 작성 후 {@code createdBefore} 이전이면서 아직 한 번도 회고하지 않은 내 기록을 최신순으로.
	 * (record↔remind 결합을 remind 인프라 쪽에만 둔다 — 기록 도메인은 리마인드를 모른다.)
	 * 호출 측에서 {@code PageRequest.of(0, 1)}로 1건만 가져간다.
	 */
	@Query("""
			select r
			from Record r
			where r.userId = :userId
			  and r.deletedAt is null
			  and r.createdAt <= :createdBefore
			  and r.id not in (
			      select rm.recordId from Remind rm
			      where rm.userId = :userId and rm.deletedAt is null
			  )
			order by r.createdAt desc
			""")
	List<Record> findRemindCandidates(
			@Param("userId") Long userId,
			@Param("createdBefore") ZonedDateTime createdBefore,
			Pageable pageable);

	/**
	 * 백그라운드 요약이 유실돼 PENDING으로 남은 리마인드 — 오래된 순(백필 대상).
	 * {@code createdBefore}로 유예를 둬 지금 막 저장돼 진행 중인 백그라운드 작업과 겹치지 않게 한다.
	 * 감정은 지연 로딩이라 호출 측이 트랜잭션 안에서 초기화한다(컬렉션 fetch join + 페이징 조합을 피함).
	 */
	@Query("""
			select rm from Remind rm
			where rm.aiStatus = :status
			  and rm.deletedAt is null
			  and rm.createdAt <= :createdBefore
			order by rm.createdAt asc
			""")
	List<Remind> findPendingOlderThan(@Param("status") RemindAiStatus status,
			@Param("createdBefore") ZonedDateTime createdBefore, Pageable pageable);

	// ── 관리자 콘솔 전용 ───────────────────────────────

	/** 전체 리마인드(회고) 수(살아있는). */
	long countByDeletedAtIsNull();

	/** 특정 사용자의 리마인드 수(관리자 상세용). */
	long countByUserIdAndDeletedAtIsNull(Long userId);

	/** 회고된(리마인드가 존재하는) 서로 다른 기록 수 — 전환율 = 이 값 / 전체 기록 수. */
	@Query("select count(distinct rm.recordId) from Remind rm where rm.deletedAt is null")
	long countDistinctRemindedRecords();

	/** 유저ID들의 리마인드 수(관리자 목록 벌크). Object[]{userId, count}. */
	@Query("select rm.userId, count(rm) from Remind rm where rm.userId in :userIds and rm.deletedAt is null group by rm.userId")
	List<Object[]> countByUserIds(@Param("userIds") List<Long> userIds);

	/** 탈퇴 cascade용: 사용자의 살아있는 리마인드 전부. */
	List<Remind> findByUserIdAndDeletedAtIsNull(Long userId);
}
