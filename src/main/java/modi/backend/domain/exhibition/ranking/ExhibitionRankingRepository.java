package modi.backend.domain.exhibition.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * - 전시 랭킹 저장소 포트 (도메인 소유, 구현은 infra — DIP)
 *   - 날짜 버킷이 랭킹의 원본, 순위판은 원본에서 다시 만드는 파생 데이터
 *   - 알고리즘은 모름: 받은 계획대로 가중 합산만 함
 *
 * - 앱 2대에서 안전해야 하는 연산
 *   - 기록: 원자적 증가라 동시에 와도 증가분이 사라지지 않아야 함
 *   - 재계산: 같은 원본에서 다시 만들어 덮어써 몇 번 실행돼도 결과가 같아야 함
 */
public interface ExhibitionRankingRepository {

	/**
	 * - 조회 1번 기록: 오늘 버킷 +1, 후보인 전시면 활성 순위판마다 +가중치
	 *   - 저장소 장애는 삼킴 (조회수 때문에 상세 응답이 실패하지 않게)
	 */
	void recordView(long exhibitionId, LocalDate today, List<BoardIncrement> increments);

	/**
	 * - 순위판을 원본에서 다시 만들어 덮어씀
	 *   - 후보 집합 교체 → 계획대로 가중 합산 → 후보만 남김, 한 번에 원자적으로
	 *
	 * @return 다시 만든 순위판의 전시 수
	 */
	long rebuild(RankingPlan plan, RankingTieBreak tieBreak);

	/** {@code keepFrom} 이전 날짜 버킷을 지움. 원본이라 TTL 대신 이 경로로만 사라짐. */
	void purgeBefore(LocalDate keepFrom);

	/** 순위판 상위 {@code size}개, 1위부터. */
	List<RankingEntry> top(RankingType type, int size);

	/** 전시 하나의 순위. 순위판에 없으면 비어 있음. */
	Optional<RankingEntry> find(RankingType type, long exhibitionId);

	/** 순위판에 오른 전시 수. */
	long count(RankingType type);
}
