package modi.backend.infra.search;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import modi.backend.domain.search.SearchHistory;

/** Spring Data JPA — 검색 기록. */
public interface SearchHistoryJpaRepository extends JpaRepository<SearchHistory, Long> {

	Optional<SearchHistory> findByUserIdAndKeyword(Long userId, String keyword);

	List<SearchHistory> findByUserIdOrderBySearchedAtDescIdDesc(Long userId, Pageable pageable);

	long deleteByUserId(Long userId);

	/** 남길 id들을 빼고 지운다 — "최신 N개만 유지"의 삭제 쪽. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from SearchHistory h where h.userId = :userId and h.id not in :keepIds")
	int deleteByUserIdAndIdNotIn(@Param("userId") Long userId, @Param("keepIds") List<Long> keepIds);
}
