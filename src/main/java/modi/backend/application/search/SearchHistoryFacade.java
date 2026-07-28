package modi.backend.application.search;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.search.SearchHistory;
import modi.backend.domain.search.SearchHistoryRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;
import modi.backend.support.time.AppTime;

/**
 * 전시 검색 기록 유스케이스 — 최근 검색어 조회·기록·삭제. 회원 전용이다.
 *
 * <p><b>기록은 검색 API가 아니라 이 경로로 들어온다</b>: 목록 조회가 쓰기를 겸하면 조회 경로가 오염되고,
 * 무엇보다 클라이언트가 타이핑 디바운스로 검색하므로 {@code "전"} {@code "전시"} 같은 중간 입력이 전부 쌓인다.
 * 검색이 <b>확정된 시점</b>(엔터·검색 버튼)에 클라이언트가 명시적으로 부른다.
 */
@Service
@RequiredArgsConstructor
public class SearchHistoryFacade {

	/** 화면에 보여줄 최근 검색어 수. 이 수를 넘는 기록은 저장 시점에 정리한다. */
	private static final int RECENT_LIMIT = 10;

	private final SearchHistoryRepository searchHistoryRepository;

	/** 최근 검색어(최신순 최대 10). 기록이 없으면 빈 목록. */
	@Transactional(readOnly = true)
	public SearchHistoryResult.RecentList getRecent(Long userId) {
		List<SearchHistoryResult.Item> content = searchHistoryRepository.findRecent(userId, RECENT_LIMIT).stream()
				.map(SearchHistoryResult.Item::from)
				.toList();
		return new SearchHistoryResult.RecentList(content);
	}

	/**
	 * 검색어를 기록한다(멱등). 같은 키워드가 이미 있으면 새 행을 만들지 않고 검색 시각만 갱신해 맨 위로 올린다.
	 * 저장 후 {@value #RECENT_LIMIT}개를 넘는 오래된 기록은 정리한다 — 조회에서만 자르면 행이 무한히 쌓인다.
	 */
	@Transactional
	public SearchHistoryResult.Recorded record(SearchHistoryCriteria.Record criteria) {
		String keyword = SearchHistory.normalize(criteria.keyword());
		LocalDateTime now = LocalDateTime.now(AppTime.KST);

		SearchHistory history = searchHistoryRepository.findByUserIdAndKeyword(criteria.userId(), keyword)
				.map(existing -> {
					existing.searchedAgain(now);
					return searchHistoryRepository.save(existing);
				})
				.orElseGet(() -> searchHistoryRepository.save(
						SearchHistory.create(criteria.userId(), keyword, now)));

		searchHistoryRepository.deleteOlderThanRecent(criteria.userId(), RECENT_LIMIT);
		return SearchHistoryResult.Recorded.from(history);
	}

	/** 개별 삭제(멱등). 이미 없으면 조용히 넘어가고, 남의 기록이면 403. */
	@Transactional
	public void delete(SearchHistoryCriteria.Delete criteria) {
		searchHistoryRepository.findById(criteria.searchHistoryId()).ifPresent(history -> {
			if (!history.isOwnedBy(criteria.userId())) {
				throw new CoreException(ErrorType.FORBIDDEN, "타인의 검색 기록 삭제: " + criteria.searchHistoryId());
			}
			searchHistoryRepository.delete(history);
		});
	}

	/** 전체 삭제(멱등). 지운 건수를 돌려준다. */
	@Transactional
	public long deleteAll(Long userId) {
		return searchHistoryRepository.deleteAllByUserId(userId);
	}
}
