package modi.backend.domain.exhibition.ranking;

import java.time.LocalDate;
import java.util.Objects;

/**
 * - 순위판에 오를 수 있는 전시 하나 (오늘 진행 중인 CATALOG)
 *   - 개막일은 동점 점수 계산에만 씀
 *   - 카탈로그 정보라 MySQL에서 읽음, 조회수는 들고 있지 않음 (조회수의 원본은 Redis)
 */
public record RankingCandidate(Long exhibitionId, LocalDate startDate) {

	public RankingCandidate {
		Objects.requireNonNull(exhibitionId, "exhibitionId");
		Objects.requireNonNull(startDate, "startDate");
	}
}
