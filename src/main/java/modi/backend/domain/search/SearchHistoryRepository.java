package modi.backend.domain.search;

import java.util.List;
import java.util.Optional;

/**
 * 검색 기록 영속화 포트(도메인 소유, 구현은 infra — DIP).
 *
 * <p>검색 기록은 <b>보관이 목적이 아니라 최근 N개를 보여주는 것이 목적</b>이라, 조회는 항상 최근순 상한이 있고
 * 저장은 초과분 정리를 동반한다. 그 정리를 포트에 두는 이유는 "무엇을 지울지"가 정렬에 달려 있어서다.
 */
public interface SearchHistoryRepository {

	SearchHistory save(SearchHistory searchHistory);

	Optional<SearchHistory> findById(Long id);

	/** 같은 사용자의 같은 키워드(정규화 기준) 기록 — 재검색 시 시각만 갱신하기 위한 조회. */
	Optional<SearchHistory> findByUserIdAndKeyword(Long userId, String keyword);

	/** 최근 검색어를 최신순으로 {@code limit}개. */
	List<SearchHistory> findRecent(Long userId, int limit);

	void delete(SearchHistory searchHistory);

	/** 해당 사용자의 기록 전부 삭제(전체 지우기). 지운 건수를 돌려준다. */
	long deleteAllByUserId(Long userId);

	/** 최신순 {@code keep}개만 남기고 오래된 기록을 지운다. 지운 건수를 돌려준다. */
	long deleteOlderThanRecent(Long userId, int keep);
}
