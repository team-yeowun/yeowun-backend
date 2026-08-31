package modi.backend.ingestionv2.lab.retry;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 반복 측정값의 집계.
 *
 * <ul>
 *   <li>p50·p95 는 선형보간(R-7, numpy 기본 - Excel PERCENTILE.INC 와 같은 정의)</li>
 *   <li>최근접 순위였다면 표본이 적을 때 p95 가 최댓값과 붙어 백분위로 읽히지 않는다</li>
 *   <li>max 를 따로 실어 보내 p95 와 최댓값이 붙었는지 독자가 직접 본다</li>
 *   <li>{@code p95_over_p50} 은 프로토콜 §4 편차 규칙(2 초과면 N 을 올린다)의 입력</li>
 * </ul>
 */
record RetryLabStats(int n, double p50, double p95, double min, double max, double mean, double stdev) {

	static final String P95_METHOD = "linear interpolation (R-7, numpy 기본)";

	static RetryLabStats of(double[] rawValues) {
		if (rawValues.length == 0) {
			return new RetryLabStats(0, 0, 0, 0, 0, 0, 0);
		}
		double[] values = rawValues.clone();
		Arrays.sort(values);
		double sum = 0;
		for (double value : values) {
			sum += value;
		}
		double mean = sum / values.length;
		double squared = 0;
		for (double value : values) {
			squared += (value - mean) * (value - mean);
		}
		double stdev = values.length < 2 ? 0 : Math.sqrt(squared / (values.length - 1));
		return new RetryLabStats(values.length, percentile(values, 0.50), percentile(values, 0.95),
				values[0], values[values.length - 1], mean, stdev);
	}

	private static double percentile(double[] sorted, double fraction) {
		if (sorted.length == 1) {
			return sorted[0];
		}
		double position = fraction * (sorted.length - 1);
		int lower = (int) Math.floor(position);
		int upper = (int) Math.ceil(position);
		if (lower == upper) {
			return sorted[lower];
		}
		return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
	}

	Map<String, Object> toAggregation() {
		Map<String, Object> aggregation = new LinkedHashMap<>();
		aggregation.put("p50", round(p50));
		aggregation.put("p95", round(p95));
		aggregation.put("min", round(min));
		aggregation.put("max", round(max));
		aggregation.put("mean", round(mean));
		aggregation.put("stdev", round(stdev));
		aggregation.put("n", n);
		aggregation.put("p95_method", P95_METHOD);
		aggregation.put("p95_over_p50", p50 == 0 ? null : round(p95 / p50));
		return aggregation;
	}

	static double round(double value) {
		return Math.round(value * 1000d) / 1000d;
	}
}
