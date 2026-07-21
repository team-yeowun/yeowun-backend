package modi.backend.infra.record;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.domain.record.Record;
import modi.backend.domain.record.WriteMode;

public interface RecordJpaRepository extends JpaRepository<Record, Long> {

	Optional<Record> findByIdAndDeletedAtIsNull(Long id);

	/** 해당 사용자가 이 전시에 대한 (살아있는) 기록을 가지고 있는지 — 전시 상세의 recorded 필드용. */
	boolean existsByUserIdAndExhibitionIdAndDeletedAtIsNull(Long userId, Long exhibitionId);

	/** 사용자의 (살아있는) 기록 수 — 프로필 통계용. */
	long countByUserIdAndDeletedAtIsNull(Long userId);

	/** 사용자가 기록을 남긴 서로 다른 전시 수(다녀온 전시) — 프로필 통계용. */
	@Query("select count(distinct r.exhibitionId) from Record r where r.userId = :userId and r.deletedAt is null")
	long countDistinctExhibitionByUserId(@Param("userId") Long userId);

	/** 사용자의 기록에 쓰인 감정 키워드 — 빈도 내림차순, 중복 제거(프로필 '나의 감정 키워드'용). */
	@Query("""
			select e.emotionCode
			from Record r join r.emotions e
			where r.userId = :userId and r.deletedAt is null
			group by e.emotionCode
			order by count(e) desc, e.emotionCode asc
			""")
	List<String> findEmotionCodesByUserIdOrderByFrequency(@Param("userId") Long userId);

	/** 특정 전시를 기록한 사람들이 남긴 감정 코드 — 빈도 내림차순(AI 질문 그라운딩용, Pageable로 상위 N개 제한). */
	@Query("""
			select e.emotionCode
			from Record r join r.emotions e
			where r.exhibitionId = :exhibitionId and r.deletedAt is null
			group by e.emotionCode
			order by count(e) desc, e.emotionCode asc
			""")
	List<String> findTopEmotionCodesByExhibitionId(@Param("exhibitionId") Long exhibitionId, Pageable pageable);

	/** 같은 카테고리 전시 기록들의 감정 코드 — 빈도 내림차순(해당 전시 기록이 없을 때 폴백 그라운딩). */
	@Query("""
			select e.emotionCode
			from Record r join r.emotions e
			where r.exhibitionCategory = :category and r.deletedAt is null
			group by e.emotionCode
			order by count(e) desc, e.emotionCode asc
			""")
	List<String> findTopEmotionCodesByCategory(@Param("category") String category, Pageable pageable);

	/** 감정까지 즉시 로딩해 반환(리마인드에서 원본 감정을 세션 밖에서 안전히 읽기 위함). */
	@Query("select distinct r from Record r left join fetch r.emotions where r.id = :id and r.deletedAt is null")
	Optional<Record> findByIdWithEmotions(@Param("id") Long id);

	@Query("""
			select distinct r
			from Record r
			left join r.keywords k
			left join r.emotions e
			where r.userId = :userId
			  and r.deletedAt is null
			  and (:keyword is null or r.content like concat('%', :keyword, '%')
			       or r.aiSummary like concat('%', :keyword, '%')
			       or k.keyword like concat('%', :keyword, '%'))
			  and (:emotion is null or e.emotionCode = :emotion)
			  and (:exhibitionId is null or r.exhibitionId = :exhibitionId)
			  and (:bookmarked is null or r.bookmarked = :bookmarked)
			  and (:writeMode is null or r.writeMode = :writeMode)
			  and (:fromViewedAt is null or r.viewedAt >= :fromViewedAt)
			  and (:toViewedAt is null or r.viewedAt <= :toViewedAt)
			""")
	Page<Record> search(
			@Param("userId") Long userId,
			@Param("keyword") String keyword,
			@Param("emotion") String emotion,
			@Param("exhibitionId") Long exhibitionId,
			@Param("bookmarked") Boolean bookmarked,
			@Param("writeMode") WriteMode writeMode,
			@Param("fromViewedAt") LocalDate fromViewedAt,
			@Param("toViewedAt") LocalDate toViewedAt,
			Pageable pageable);

	// ── 관리자 콘솔 전용 ───────────────────────────────

	/** 전체 기록 수(살아있는). */
	long countByDeletedAtIsNull();

	/** 일자별 기록 생성 수(created_at UTC 일자). Object[]{date, count}. */
	@Query("select function('date', r.createdAt), count(r) from Record r "
			+ "where r.deletedAt is null and r.createdAt >= :from "
			+ "group by function('date', r.createdAt) order by function('date', r.createdAt)")
	List<Object[]> countByDaySince(@Param("from") ZonedDateTime from);

	/** 전체 인기 감정 Top-N(emotionCode, 빈도). Pageable로 상위 N개. Object[]{emotionCode, count}. */
	@Query("select e.emotionCode, count(e) from Record r join r.emotions e "
			+ "where r.deletedAt is null group by e.emotionCode order by count(e) desc, e.emotionCode asc")
	List<Object[]> topEmotions(Pageable pageable);

	/** 유저ID들의 기록 수(관리자 목록 벌크). Object[]{userId, count}. */
	@Query("select r.userId, count(r) from Record r where r.userId in :userIds and r.deletedAt is null group by r.userId")
	List<Object[]> countByUserIds(@Param("userIds") List<Long> userIds);

	/** 사용자 상세: 최근 기록(페이지). */
	Page<Record> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

	/** 탈퇴 cascade용: 사용자의 살아있는 기록 전부. */
	List<Record> findByUserIdAndDeletedAtIsNull(Long userId);
}
