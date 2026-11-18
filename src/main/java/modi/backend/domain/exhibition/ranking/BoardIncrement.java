package modi.backend.domain.exhibition.ranking;

import java.util.Objects;

/** 조회 1번이 순위판 하나에 더할 점수. 오늘 버킷의 가중치와 같음. */
public record BoardIncrement(RankingType type, double weight) {

	public BoardIncrement {
		Objects.requireNonNull(type, "type");
		if (!(weight > 0)) {
			throw new IllegalArgumentException("순위판 증가분은 0보다 커야 함: " + weight);
		}
	}
}
