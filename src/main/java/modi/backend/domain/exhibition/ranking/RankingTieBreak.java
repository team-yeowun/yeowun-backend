package modi.backend.domain.exhibition.ranking;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * - 조회수가 같은 전시끼리 순서를 정하는 아주 작은 점수 (후보 전원이 받음)
 *   - 개막일이 최근일수록 큼 → 최근 조회가 없을 때는 새로 연 전시가 앞에 섬
 *   - 0 초과 상한 미만 → 조회 1번이 늘 이 점수보다 커서 조회수 순서는 그대로
 *
 * - (개막일, id) 오름차순으로 줄 세운 뒤 순번에 비례해 배정
 *   - 같은 후보 목록이면 두 앱이 같은 값을 계산 (재계산 멱등의 전제)
 *   - 개막일이 같아도 id로 갈려 순위판에 동점이 남지 않음
 *
 * - 카탈로그 정보(개막일)만 씀 → 조회수의 원본은 Redis 하나로 유지
 */
public record RankingTieBreak(Map<Long, Double> scores) {

	public RankingTieBreak {
		scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
	}

	public static RankingTieBreak byNewestOpening(List<RankingCandidate> candidates, double cap) {
		if (!(cap > 0)) {
			throw new IllegalArgumentException("동점 점수 상한은 0보다 커야 함: " + cap);
		}
		List<RankingCandidate> ordered = candidates.stream()
				.sorted(Comparator.comparing(RankingCandidate::startDate)
						.thenComparing(RankingCandidate::exhibitionId))
				.toList();
		Map<Long, Double> scores = new LinkedHashMap<>();
		int slots = ordered.size() + 1;
		for (int i = 0; i < ordered.size(); i++) {
			scores.put(ordered.get(i).exhibitionId(), cap * (i + 1) / slots);
		}
		return new RankingTieBreak(scores);
	}
}
