package modi.backend.infra.search;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.search.SearchHistory;
import modi.backend.domain.search.SearchHistoryRepository;

/**
 * {@link SearchHistoryRepository} 어댑터(DIP). 최근순 상한·초과분 정리를 모두 SQL에 맡긴다 —
 * 앱에서 잘라내면 지울 대상을 고르려고 전량을 읽어야 한다.
 */
@Repository
@RequiredArgsConstructor
public class SearchHistoryRepositoryImpl implements SearchHistoryRepository {

	private final SearchHistoryJpaRepository jpaRepository;

	@Override
	public SearchHistory save(SearchHistory searchHistory) {
		return jpaRepository.save(searchHistory);
	}

	@Override
	public Optional<SearchHistory> findById(Long id) {
		return jpaRepository.findById(id);
	}

	@Override
	public Optional<SearchHistory> findByUserIdAndKeyword(Long userId, String keyword) {
		return jpaRepository.findByUserIdAndKeyword(userId, keyword);
	}

	@Override
	public List<SearchHistory> findRecent(Long userId, int limit) {
		return jpaRepository.findByUserIdOrderBySearchedAtDescIdDesc(userId, PageRequest.of(0, Math.max(1, limit)));
	}

	@Override
	public void delete(SearchHistory searchHistory) {
		jpaRepository.delete(searchHistory);
	}

	@Override
	public long deleteAllByUserId(Long userId) {
		return jpaRepository.deleteByUserId(userId);
	}

	/**
	 * 최신 {@code keep}개의 id를 먼저 뽑고 그 밖을 지운다. 삭제 조건을 "오래된 것"으로 직접 쓰려면
	 * 기준 시각을 알아야 하는데, 그 시각을 구하는 것이 결국 같은 조회다 — id 목록이 더 단순하고 정확하다.
	 */
	@Override
	public long deleteOlderThanRecent(Long userId, int keep) {
		List<Long> keepIds = findRecent(userId, keep).stream().map(SearchHistory::getId).toList();
		if (keepIds.isEmpty()) {
			return 0;
		}
		return jpaRepository.deleteByUserIdAndIdNotIn(userId, keepIds);
	}
}
