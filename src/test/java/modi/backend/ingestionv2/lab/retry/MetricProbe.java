package modi.backend.ingestionv2.lab.retry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * step-08 채취 훅 - 관측창 전후의 미터 스냅샷과 계기 시계열을 뜬다.
 *
 * <ul>
 *   <li>레지스트리에서 <b>이름으로</b> 훑는다 - 카운터 하나를 새로 붙일 때마다 하네스를 고치지 않게</li>
 *   <li>한 번도 오르지 않은 카운터는 <b>등록조차 되어 있지 않다</b> - 델타는 전후 키의 합집합에서 뜨고
 *       없는 쪽을 0 으로 읽는다. 이 규칙이 없으면 "0 건" 과 "미부착" 이 구분되지 않는다</li>
 *   <li>계기 표본은 별도 스레드가 뜬다 - 측정 본체가 틱을 미는 동안 1s 격자를 지키려면 그 방법뿐이다.
 *       실패는 삼키고 표본에 사유를 남긴다(계기 하나 때문에 측정이 끝나지 않게)</li>
 *   <li>대조는 <b>이 클래스가 판정하지 않는다</b> - 카운터 값과 DB 실측을 나란히 적을 뿐, 합격 여부는 실험자 몫</li>
 * </ul>
 */
final class MetricProbe {

	/** 스냅샷에 담을 미터 이름 접두사. 슬라이스 밖 미터(JVM·HikariCP 등)는 담지 않는다. */
	private static final String PREFIX = "ingestion.";

	private final MeterRegistry meterRegistry;

	MetricProbe(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/** 관측창 한쪽 끝의 값 전부. 카운터·타이머·계기를 이름+태그 키로 편다. */
	Snapshot snapshot() {
		Map<String, Double> counters = new LinkedHashMap<>();
		Map<String, Double> timerCounts = new LinkedHashMap<>();
		Map<String, Double> timerTotals = new LinkedHashMap<>();
		Map<String, Double> gauges = new LinkedHashMap<>();
		for (Meter meter : sortedMeters()) {
			String key = keyOf(meter.getId().getName(), meter.getId().getTags());
			if (meter instanceof Counter counter) {
				counters.put(key, counter.count());
			} else if (meter instanceof Timer timer) {
				timerCounts.put(key, (double) timer.count());
				timerTotals.put(key, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
			} else if (meter instanceof Gauge gauge) {
				gauges.put(key, gauge.value());
			}
		}
		return new Snapshot(counters, timerCounts, timerTotals, gauges);
	}

	private List<Meter> sortedMeters() {
		List<Meter> meters = new ArrayList<>(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().startsWith(PREFIX))
				.toList());
		meters.sort(Comparator.comparing(meter -> keyOf(meter.getId().getName(), meter.getId().getTags())));
		return meters;
	}

	/** 계기를 1s 격자로 뜬다. {@link Sampler#stop()} 이 시계열을 돌려준다. */
	Sampler sampleGauges(long periodMillis) {
		return new Sampler(this, periodMillis);
	}

	/** 관측창 전후 스냅샷의 차. 원시 파일 {@code metrics} 필드에 그대로 들어간다. */
	static Map<String, Object> delta(Snapshot before, Snapshot after) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("counters", diff(before.counters(), after.counters()));

		Map<String, Object> timers = new LinkedHashMap<>();
		for (String key : union(before.timerCounts(), after.timerCounts())) {
			double count = value(after.timerCounts(), key) - value(before.timerCounts(), key);
			double totalMs = value(after.timerTotals(), key) - value(before.timerTotals(), key);
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("count", round(count));
			entry.put("total_ms", round(totalMs));
			entry.put("mean_ms", count <= 0 ? null : round(totalMs / count));
			timers.put(key, entry);
		}
		result.put("timers", timers);
		result.put("gauges_at_window_start", round(before.gauges()));
		result.put("gauges_at_window_end", round(after.gauges()));
		return result;
	}

	/**
	 * 카운터 하나의 델타 - 이름이 같고 <b>요청한 태그를 포함하는</b> 카운터 전부의 합.
	 *
	 * <ul>
	 *   <li>정확 일치로 찾지 않는다 - 레지스트리에 공통 태그(`application=modi-backend`,
	 *       {@code application.yaml} 의 {@code management.metrics.tags})가 자동으로 붙어 실제 키가
	 *       {@code ...{application=modi-backend,reason=exhausted}} 가 된다. 정확 일치는 조용히 0 을 돌려준다(H-08-01)</li>
	 *   <li>없으면 0 - 미등록(한 번도 오르지 않음)과 값 0 은 같은 결과다. 어느 쪽인지는
	 *       {@link #matchedKeys} 가 답한다</li>
	 * </ul>
	 */
	static double counterDelta(Snapshot before, Snapshot after, String name, String tagKey, String tagValue) {
		double sum = 0;
		for (String key : matchedKeys(before, after, name, tagKey, tagValue)) {
			sum += value(after.counters(), key) - value(before.counters(), key);
		}
		return sum;
	}

	/** 위 델타가 실제로 집어 든 키 목록 - 0 이 "미부착"인지 "값 0"인지 원시 파일에서 갈리게 한다. */
	static List<String> matchedKeys(Snapshot before, Snapshot after, String name, String tagKey, String tagValue) {
		String wanted = tagKey + "=" + tagValue;
		List<String> keys = new ArrayList<>();
		for (String key : union(before.counters(), after.counters())) {
			if (!key.startsWith(name + "{") || !key.endsWith("}")) {
				continue;
			}
			String tags = key.substring(name.length() + 1, key.length() - 1);
			if (List.of(tags.split(",")).contains(wanted)) {
				keys.add(key);
			}
		}
		return keys;
	}

	/** 이름이 같은 카운터 전부의 델타 합(태그 무관). */
	static double counterDelta(Snapshot before, Snapshot after, String name) {
		double sum = 0;
		for (String key : union(before.counters(), after.counters())) {
			if (key.equals(name) || key.startsWith(name + "{")) {
				sum += value(after.counters(), key) - value(before.counters(), key);
			}
		}
		return sum;
	}

	/**
	 * 대조 한 줄 - 카운터 델타와 실측을 나란히 두고 차이만 적는다. 합격 판정은 하지 않는다.
	 *
	 * <p>{@code matched_keys} 가 비어 있으면 그 0 은 "격리가 없었다"가 아니라 <b>조회가 빗나갔다</b>는 뜻이다.
	 * H-08-01 이 정확히 그 경우였고, 그때 원시 파일만 보고는 구분할 수 없었다.
	 */
	static Map<String, Object> reconcile(String metric, double counterDelta, long measured, String measuredBy,
			List<String> matchedKeys) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("metric", metric);
		row.put("counter_delta", round(counterDelta));
		row.put("measured", measured);
		row.put("measured_by", measuredBy);
		row.put("difference", round(counterDelta - measured));
		row.put("matched_keys", matchedKeys);
		return row;
	}

	private static Map<String, Object> diff(Map<String, Double> before, Map<String, Double> after) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (String key : union(before, after)) {
			result.put(key, round(value(after, key) - value(before, key)));
		}
		return result;
	}

	private static Set<String> union(Map<String, Double> before, Map<String, Double> after) {
		Set<String> keys = new LinkedHashSet<>(before.keySet());
		keys.addAll(after.keySet());
		return keys;
	}

	private static double value(Map<String, Double> values, String key) {
		Double value = values.get(key);
		return value == null ? 0d : value;
	}

	private static Map<String, Object> round(Map<String, Double> values) {
		Map<String, Object> result = new LinkedHashMap<>();
		values.forEach((key, value) -> result.put(key, round(value)));
		return result;
	}

	private static double round(double value) {
		return Math.round(value * 1000d) / 1000d;
	}

	private static String keyOf(String name, Iterable<Tag> tags) {
		StringBuilder key = new StringBuilder(name);
		List<String> parts = new ArrayList<>();
		tags.forEach(tag -> parts.add(tag.getKey() + "=" + tag.getValue()));
		if (parts.isEmpty()) {
			return key.toString();
		}
		parts.sort(Comparator.naturalOrder());
		return key.append('{').append(String.join(",", parts)).append('}').toString();
	}

	/** 미터 값의 한 시점. */
	record Snapshot(Map<String, Double> counters, Map<String, Double> timerCounts,
			Map<String, Double> timerTotals, Map<String, Double> gauges) {
	}

	/**
	 * 계기 1s 표본 수집기.
	 *
	 * <ul>
	 *   <li>데몬 스레드 - 측정이 예외로 끝나도 JVM 을 붙잡지 않는다</li>
	 *   <li>표본 하나 = {@code {t_ms, 계기별 값}} · 읽기 실패는 {@code error} 로 남기고 계속</li>
	 * </ul>
	 */
	static final class Sampler {

		private final MetricProbe probe;
		private final long periodMillis;
		private final List<Map<String, Object>> samples = new ArrayList<>();
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final long startedAt = System.nanoTime();
		private final Thread thread;

		private Sampler(MetricProbe probe, long periodMillis) {
			this.probe = probe;
			this.periodMillis = periodMillis;
			this.thread = new Thread(this::loop, "lab-gauge-sampler");
			this.thread.setDaemon(true);
			this.thread.start();
		}

		private void loop() {
			while (running.get()) {
				take();
				try {
					Thread.sleep(periodMillis);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}

		private void take() {
			Map<String, Object> sample = new LinkedHashMap<>();
			sample.put("t_ms", (System.nanoTime() - startedAt) / 1_000_000L);
			try {
				probe.snapshot().gauges().forEach((key, value) -> sample.put(key, round(value)));
			} catch (RuntimeException unavailable) {
				sample.put("error", String.valueOf(unavailable.getMessage()));
			}
			synchronized (samples) {
				samples.add(sample);
			}
		}

		/** 마지막 표본을 한 번 더 뜨고 멈춘다. 돌려주는 목록이 시계열 그대로다. */
		List<Map<String, Object>> stop() {
			running.set(false);
			thread.interrupt();
			take();
			synchronized (samples) {
				return List.copyOf(samples);
			}
		}
	}
}
