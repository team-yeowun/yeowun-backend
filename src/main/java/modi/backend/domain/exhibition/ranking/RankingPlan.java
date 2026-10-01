package modi.backend.domain.exhibition.ranking;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * - 순위판 하나를 만드는 날짜 버킷과 가중치 목록
 *   - 전략이 만들고, 저장소는 이 목록대로 가중 합산만 함
 *   - 버킷 1개 이상, 같은 날짜 중복 없음 (위반하면 기동·재계산 시점에 바로 드러남)
 *
 * - 동점 점수 상한 = 가장 작은 버킷 가중치의 절반
 *   - 어느 날의 조회 1번도 동점 점수보다 큼 → 동점 점수가 조회수 순서를 뒤집지 못함
 *   - 3일 + 10%면 0.1 / 2 = 0.05, 알고리즘이 바뀌어도 상한은 자동으로 따라감
 */
public record RankingPlan(RankingType type, List<WeightedBucket> buckets) {

	public RankingPlan {
		Objects.requireNonNull(type, "type");
		if (buckets == null || buckets.isEmpty()) {
			throw new IllegalArgumentException("순위 계획에는 날짜 버킷이 1개 이상 있어야 함: " + type);
		}
		buckets = List.copyOf(buckets);
		long distinctDays = buckets.stream().map(WeightedBucket::day).distinct().count();
		if (distinctDays != buckets.size()) {
			throw new IllegalArgumentException("같은 날짜 버킷이 두 번 들어옴: " + type);
		}
	}

	/** 그날 조회 1번이 이 순위판에 더할 점수. 창 밖 날짜면 0. */
	public double weightOf(LocalDate day) {
		return buckets.stream()
				.filter(bucket -> bucket.day().equals(day))
				.mapToDouble(WeightedBucket::weight)
				.findFirst()
				.orElse(0);
	}

	/** 동점 점수가 넘으면 안 되는 상한. */
	public double tieBreakCap() {
		return buckets.stream().mapToDouble(WeightedBucket::weight).min().orElseThrow() / 2;
	}

	/** 이 계획이 읽는 가장 오래된 날짜. 날짜 버킷 보존 기간의 기준. */
	public LocalDate oldestDay() {
		return buckets.stream().map(WeightedBucket::day).min(Comparator.naturalOrder()).orElseThrow();
	}
}
