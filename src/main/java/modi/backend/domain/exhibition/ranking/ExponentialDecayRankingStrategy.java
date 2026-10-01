package modi.backend.domain.exhibition.ranking;

import java.time.LocalDate;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * - 오래된 조회일수록 가중치가 반씩 줄어드는 알고리즘
 *   - 가중치 = 0.5^(경과일 / 반감기), 오늘이 1
 *   - 창 경계에서 뚝 끊기지 않고 서서히 빠져서 "요즘 뜨는" 전시를 앞에 둠
 *
 * - 창 길이(horizonDays) 밖은 가중치가 충분히 작아 버림
 */
public final class ExponentialDecayRankingStrategy implements RankingStrategy {

	private final RankingType type;
	private final double halfLifeDays;
	private final int horizonDays;

	public ExponentialDecayRankingStrategy(RankingType type, double halfLifeDays, int horizonDays) {
		this.type = Objects.requireNonNull(type, "type");
		if (!(halfLifeDays > 0)) {
			throw new IllegalArgumentException("반감기는 0보다 커야 함: " + halfLifeDays);
		}
		if (horizonDays < 1) {
			throw new IllegalArgumentException("창 크기는 1일 이상이어야 함: " + horizonDays);
		}
		this.halfLifeDays = halfLifeDays;
		this.horizonDays = horizonDays;
	}

	/** 반감기 1일, 최근 7일. */
	public static ExponentialDecayRankingStrategy trending() {
		return new ExponentialDecayRankingStrategy(RankingType.TRENDING, 1.0, 7);
	}

	@Override
	public RankingType type() {
		return type;
	}

	@Override
	public RankingPlan plan(LocalDate today) {
		return new RankingPlan(type, IntStream.range(0, horizonDays)
				.mapToObj(age -> new WeightedBucket(today.minusDays(age), Math.pow(0.5, age / halfLifeDays)))
				.toList());
	}
}
