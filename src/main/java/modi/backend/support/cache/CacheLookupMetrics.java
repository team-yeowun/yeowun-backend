package modi.backend.support.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * - 캐시 조회가 어느 계층에서 끝났는지 캐시별로 센다
 *   - L1 히트 · L2 히트 · 전부 미스 셋
 *   - 창구({@link CacheManager})가 그 판정을 하는 유일한 자리라 여기서만 셀 수 있음
 *
 * - Redis의 {@code keyspace_hits}로는 이 값을 낼 수 없음
 *   - 그 지표는 인스턴스 전체 것이라 조회수 누산·AI 임시저장까지 섞여 있음
 *   - 그걸 캐시 히트율이라고 부르면 틀린 숫자를 보고하게 됨
 *   - 그래서 창구가 직접 세고, 대시보드는 이 값을 읽음
 *
 * - 두 벌로 들고 있음
 *   - Micrometer 카운터: Prometheus·Grafana로 나가는 시계열
 *   - {@link LongAdder} 스냅샷: 관리자 대시보드가 한 번에 읽을 현재 누계
 *   - 레지스트리를 뒤져 태그별 값을 모으는 것보다 이쪽이 단순함
 */
@Component
public class CacheLookupMetrics {

	private static final String LOOKUP = "modi.cache.lookup";

	private final MeterRegistry meterRegistry;
	private final Map<String, Counts> byCache = new ConcurrentHashMap<>();

	public CacheLookupMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/** 캐시 하나의 계층별 누계. */
	public static final class Counts {
		private final LongAdder l1 = new LongAdder();
		private final LongAdder l2 = new LongAdder();
		private final LongAdder miss = new LongAdder();

		public long l1Hits() {
			return l1.sum();
		}

		public long l2Hits() {
			return l2.sum();
		}

		public long misses() {
			return miss.sum();
		}

		public long total() {
			return l1Hits() + l2Hits() + misses();
		}

		/**
		 * - 전체 히트율 = (L1 + L2) / 전체
		 *   - 조회가 없었으면 0이 아니라 -1을 돌려 "아직 모른다"를 구분함
		 *   - 0%로 표시하면 "다 미스났다"로 읽혀 오해가 생김
		 */
		public double hitRate() {
			long t = total();
			return t == 0 ? -1 : (double) (l1Hits() + l2Hits()) / t;
		}
	}

	public void l1Hit(MyCache cache) {
		counts(cache).l1.increment();
		meterRegistry.counter(LOOKUP, "cache", cache.getName(), "result", "l1_hit").increment();
	}

	public void l2Hit(MyCache cache) {
		counts(cache).l2.increment();
		meterRegistry.counter(LOOKUP, "cache", cache.getName(), "result", "l2_hit").increment();
	}

	public void miss(MyCache cache) {
		counts(cache).miss.increment();
		meterRegistry.counter(LOOKUP, "cache", cache.getName(), "result", "miss").increment();
	}

	/** 대시보드가 읽는 현재 누계. 등록된 적 없는 캐시면 0으로 채운 빈 값. */
	public Counts snapshot(MyCache cache) {
		return byCache.getOrDefault(cache.getName(), new Counts());
	}

	private Counts counts(MyCache cache) {
		return byCache.computeIfAbsent(cache.getName(), name -> new Counts());
	}
}
