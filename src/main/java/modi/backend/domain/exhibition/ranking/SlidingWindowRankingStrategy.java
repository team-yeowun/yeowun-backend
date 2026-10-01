package modi.backend.domain.exhibition.ranking;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * - 최근 N일을 같은 가중치로 합치고, 창 바로 밖 하루를 작은 가중치로 한 번 더 넣는 알고리즘
 *   - 최근 3일 + 10%: 자정에 하루치가 통째로 0이 되지 않아 순위가 급변하지 않음
 *   - 오늘 하루 · 최근 7일도 같은 클래스의 설정으로 표현
 *
 * - 10%를 전날 점수에 미리 더해 두는 이월 방식이 아니라 계산식으로 둔 이유
 *   - 이월은 누적 연산이라 두 번 실행되면 점수가 두 배가 됨 (앱 2대에서 정확히 한 번을 따로 보장해야 함)
 *   - 계산식이면 원본에서 몇 번을 다시 계산해도 결과가 같음
 */
public final class SlidingWindowRankingStrategy implements RankingStrategy {

	private final RankingType type;
	private final int days;
	private final double tailWeight;

	public SlidingWindowRankingStrategy(RankingType type, int days, double tailWeight) {
		this.type = Objects.requireNonNull(type, "type");
		if (days < 1) {
			throw new IllegalArgumentException("창 크기는 1일 이상이어야 함: " + days);
		}
		if (tailWeight < 0 || tailWeight >= 1) {
			throw new IllegalArgumentException("창 밖 가중치는 0 이상 1 미만이어야 함: " + tailWeight);
		}
		this.days = days;
		this.tailWeight = tailWeight;
	}

	/** 최근 3일 + 창 밖 하루 10%. */
	public static SlidingWindowRankingStrategy recent3d() {
		return new SlidingWindowRankingStrategy(RankingType.RECENT_3D, 3, 0.1);
	}

	/** 오늘 하루. */
	public static SlidingWindowRankingStrategy daily() {
		return new SlidingWindowRankingStrategy(RankingType.DAILY, 1, 0);
	}

	/** 최근 7일. */
	public static SlidingWindowRankingStrategy weekly() {
		return new SlidingWindowRankingStrategy(RankingType.WEEKLY, 7, 0);
	}

	@Override
	public RankingType type() {
		return type;
	}

	@Override
	public RankingPlan plan(LocalDate today) {
		List<WeightedBucket> buckets = new ArrayList<>(days + 1);
		for (int age = 0; age < days; age++) {
			buckets.add(new WeightedBucket(today.minusDays(age), 1.0));
		}
		if (tailWeight > 0) {
			buckets.add(new WeightedBucket(today.minusDays(days), tailWeight));
		}
		return new RankingPlan(type, buckets);
	}
}
