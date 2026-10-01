package modi.backend.domain.exhibition.ranking;

import java.time.LocalDate;
import java.util.Objects;

/**
 * - 순위판 계산에 들어가는 날짜 버킷 하나와 그 가중치
 *   - 날짜 버킷 = 그날 전시별 조회수 (랭킹의 원본)
 *   - 가중치 0은 "안 넣음"과 같아 받지 않음
 */
public record WeightedBucket(LocalDate day, double weight) {

	public WeightedBucket {
		Objects.requireNonNull(day, "day");
		if (!(weight > 0) || Double.isInfinite(weight)) {
			throw new IllegalArgumentException("버킷 가중치는 0보다 큰 유한한 값이어야 함: " + weight);
		}
	}
}
