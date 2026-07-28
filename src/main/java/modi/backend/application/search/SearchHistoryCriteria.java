package modi.backend.application.search;

/** 검색 기록 유스케이스 입력 모음. (Request →[Controller] Criteria → Facade) */
public final class SearchHistoryCriteria {

	private SearchHistoryCriteria() {
	}

	/** 검색어 기록 입력. 정규화·검증은 도메인({@code SearchHistory.normalize})이 한다. */
	public record Record(Long userId, String keyword) {
	}

	/** 개별 삭제 입력 — 남의 기록을 지우지 못하도록 소유자 판정에 userId를 함께 싣는다. */
	public record Delete(Long userId, Long searchHistoryId) {
	}
}
