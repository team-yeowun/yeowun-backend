package modi.backend.application.search;

import java.time.LocalDateTime;
import java.util.List;

import modi.backend.domain.search.SearchHistory;

/** 검색 기록 유스케이스 출력 모음. (Facade는 Result까지만) */
public final class SearchHistoryResult {

	private SearchHistoryResult() {
	}

	/**
	 * 최근 검색어 목록. 최대 개수가 고정(10)이라 커서를 두지 않는다 —
	 * 더 볼 것이 없는 목록에 페이지네이션을 얹으면 클라이언트만 복잡해진다.
	 */
	public record RecentList(List<Item> content) {
	}

	/** 최근 검색어 한 건. */
	public record Item(Long searchHistoryId, String keyword, LocalDateTime searchedAt) {

		public static Item from(SearchHistory history) {
			return new Item(history.getId(), history.getKeyword(), history.getSearchedAt());
		}
	}

	/** 기록 결과 — 클라이언트가 목록을 다시 부르지 않고 화면을 갱신할 수 있게 저장된 항목을 돌려준다. */
	public record Recorded(Long searchHistoryId, String keyword) {

		public static Recorded from(SearchHistory history) {
			return new Recorded(history.getId(), history.getKeyword());
		}
	}
}
