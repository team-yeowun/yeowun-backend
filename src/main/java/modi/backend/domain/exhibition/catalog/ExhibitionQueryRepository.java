package modi.backend.domain.exhibition.catalog;

import java.time.LocalDate;
import java.util.List;

import modi.backend.domain.exhibition.ranking.RankingCandidate;

/**
 * 전시 서빙 조회 전용 포트(도메인 소유, 구현은 infra). 쓰기는 {@link ExhibitionRepository}(애그리거트 루트)가 맡고,
 * 목록/탐색/배너처럼 필터·정렬·페이지네이션이 핵심인 읽기 경로만 여기로 분리한다 —
 * 애그리거트 통째 로딩이 목록 조회 성능(커버링 인덱스·키셋)을 되물리지 않게 하는 경계다.
 */
public interface ExhibitionQueryRepository {

	/**
	 * 키셋 한 페이지 조회 — {@code query.sort} 순서로 정렬하고 {@code cursorKey/cursorId} 이후 행만 본다.
	 * hasNext 판단을 위해 {@code limitPlusOne}(=size+1)개까지 가져온다. CUSTOM 노출은 {@code requesterId}로 필터링.
	 */
	List<Exhibition> searchSlice(ExhibitionQuery query, int limitPlusOne);

	/** 커서 경계를 뺀 필터 전체 건수(totalCount용). */
	long count(ExhibitionQuery query);

	/** 필터에 맞는 후보 전체(정렬·커서 미적용). 거리순처럼 앱 레이어에서 정렬·페이징하는 경로용. */
	List<Exhibition> searchAll(ExhibitionQuery query);

	/**
	 * - 랭킹 후보: {@code onDate}에 진행 중(startDate ≤ onDate ≤ endDate)인 CATALOG 전시의 id와 개막일
	 *   - 살아있는 행만, 날짜 미상은 뺌 (홈 배너에 언제 열리고 닫히는지 모르는 전시를 올리지 않음)
	 *   - 조회수는 읽지 않음 (랭킹 조회수의 원본은 Redis)
	 */
	List<RankingCandidate> findOngoingCatalogCandidates(LocalDate onDate);
}
