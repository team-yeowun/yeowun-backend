package modi.backend.infra.bookmark;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.domain.bookmark.ExhibitionBookmark;

public interface ExhibitionBookmarkJpaRepository extends JpaRepository<ExhibitionBookmark, Long> {

	/** (user, exhibition) 한 쌍은 유니크 — 상태(활성/해제) 무관하게 단건 조회(멱등 토글용). */
	Optional<ExhibitionBookmark> findByUserIdAndExhibitionId(Long userId, Long exhibitionId);

	/** 활성 북마크 존재 여부 — 전 컬럼을 읽어 앱에서 판정하지 않는다. */
	boolean existsByUserIdAndExhibitionIdAndDeletedAtIsNull(Long userId, Long exhibitionId);

	long countByUserIdAndDeletedAtIsNull(Long userId);

	@Query("select b.exhibitionId from ExhibitionBookmark b "
			+ "where b.userId = :userId and b.exhibitionId in :exhibitionIds and b.deletedAt is null")
	List<Long> findActiveExhibitionIdsIn(@Param("userId") Long userId,
			@Param("exhibitionIds") Collection<Long> exhibitionIds);

	@Query("select b.exhibitionId from ExhibitionBookmark b "
			+ "where b.userId = :userId and b.deletedAt is null order by b.createdAt desc, b.id desc")
	List<Long> findActiveExhibitionIdsOrderByRegisteredDesc(@Param("userId") Long userId);

	/**
	 * 등록 최신순 <b>한 페이지</b>의 전시 id(첫 페이지). 정렬 키(created_at, id)가 이 테이블 자기 컬럼이라
	 * 전량을 읽지 않고 DB에서 잘라 온다.
	 */
	@Query("select b.exhibitionId from ExhibitionBookmark b "
			+ "where b.userId = :userId and b.deletedAt is null order by b.createdAt desc, b.id desc")
	List<Long> findActiveExhibitionIdsFirstPage(@Param("userId") Long userId, Pageable pageable);

	/** 등록 최신순 다음 페이지 — 커서 행 {@code (createdAt, id)}보다 뒤(더 오래된 등록)만. */
	@Query("select b.exhibitionId from ExhibitionBookmark b "
			+ "where b.userId = :userId and b.deletedAt is null "
			+ "and (b.createdAt < :createdAt or (b.createdAt = :createdAt and b.id < :bookmarkId)) "
			+ "order by b.createdAt desc, b.id desc")
	List<Long> findActiveExhibitionIdsAfter(@Param("userId") Long userId,
			@Param("createdAt") ZonedDateTime createdAt, @Param("bookmarkId") Long bookmarkId, Pageable pageable);

	/** 유저ID들의 활성 북마크 수(관리자 목록 벌크). Object[]{userId, count}. */
	@Query("select b.userId, count(b) from ExhibitionBookmark b "
			+ "where b.userId in :userIds and b.deletedAt is null group by b.userId")
	List<Object[]> countByUserIds(@Param("userIds") List<Long> userIds);

	/** 탈퇴 cascade용: 사용자의 살아있는 북마크 전부. */
	List<ExhibitionBookmark> findByUserIdAndDeletedAtIsNull(Long userId);
}
