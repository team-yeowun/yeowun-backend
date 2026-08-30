package modi.backend.ingestionv2.lab.retry;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;
import modi.backend.support.error.CoreException;

/**
 * 실패를 주입하는 lab 소비자 - step-04 의 재전달과 step-05 의 소진을 만들어 내는 장치.
 *
 * <ul>
 *   <li>{@code RATE}·{@code OUTAGE} 는 <b>소진이 아닌</b> 오류로 던진다 - 소비 어댑터가 격리하지 않고 미확인으로
 *       남겨 PEL 에 쌓이고, 그것이 곧 회수·재전달의 원재료다</li>
 *   <li>{@code EXHAUSTED} 는 {@code RETRY_EXHAUSTED} 로 던진다 - 도메인이 상한을 소진했다고 배달 계층에
 *       알리는 계층 간 약속이고, 그 신호를 받은 쪽이 격리한다</li>
 *   <li>난수는 시드를 고정한 {@link Random} 하나 - 드라이버가 단일 스레드로 틱을 밀어 호출 순서가 정해져 있고,
 *       그래서 변형 사이에 같은 실패 패턴이 재현된다</li>
 *   <li>핸들러 진입 시각을 전부 남긴다 - 초당 재전달 시계열과 100ms 버킷 히스토그램의 원재료</li>
 *   <li>빈 하나를 재구성해 쓴다 - 스텝마다 컨텍스트를 새로 띄우면 기동 시간이 측정 시간을 넘는다</li>
 * </ul>
 */
final class FaultInjectingEventHandler implements IngestionEventHandler {

	enum Mode {
		/** 전달마다 고정 확률로 실패(계획서 step-04 조건 A). */
		RATE,
		/** 창 시작 뒤 장애 구간 동안 100% 실패, 이후 정상(계획서 step-04 조건 B). */
		OUTAGE,
		/** 항상 소진으로 실패 - 격리 경로를 여는 신호(계획서 step-05 시나리오 B). */
		EXHAUSTED,
		/** 항상 성공 - 회생 확인용(계획서 step-05 시나리오 C). */
		HEALTHY
	}

	private final IngestionEventType claimed;
	private final List<Attempt> attempts = new ArrayList<>();
	private final AtomicInteger successes = new AtomicInteger();
	private final AtomicInteger failures = new AtomicInteger();

	private Mode mode = Mode.HEALTHY;
	private int failurePercent;
	private long outageNanos;
	private Random random = new Random(0L);
	private long windowStartNanos = System.nanoTime();

	FaultInjectingEventHandler(IngestionEventType claimed) {
		this.claimed = claimed;
	}

	/** 실패 주입 방식을 갈아끼우고 관측 기록을 비운다. 창 기준점도 여기서 다시 잡는다. */
	void configure(Mode mode, int failurePercent, long outageNanos, long seed) {
		this.mode = mode;
		this.failurePercent = failurePercent;
		this.outageNanos = outageNanos;
		this.random = new Random(seed);
		reset();
	}

	/** 관측창의 기준점 - 적재가 끝난 직후에 맞춘다. 적재 시간이 시계열에 섞이지 않게. */
	void startWindow() {
		this.windowStartNanos = System.nanoTime();
	}

	@Override
	public boolean supports(IngestionEventType type) {
		return claimed == type;
	}

	@Override
	public void handle(String aggregateId) {
		long at = System.nanoTime() - windowStartNanos;
		Mode decided = decide(at);
		attempts.add(new Attempt(at, aggregateId, decided != Mode.HEALTHY));
		switch (decided) {
			case EXHAUSTED -> {
				failures.incrementAndGet();
				throw new CoreException(IngestionErrorCode.RETRY_EXHAUSTED,
						"lab 소진 주입 aggregateId=" + aggregateId);
			}
			case HEALTHY -> successes.incrementAndGet();
			default -> {
				failures.incrementAndGet();
				throw new CoreException(IngestionErrorCode.STREAM_PUBLISH_FAILED,
						"lab 실패 주입 aggregateId=" + aggregateId);
			}
		}
	}

	private Mode decide(long elapsedNanos) {
		return switch (mode) {
			case RATE -> random.nextInt(100) < failurePercent ? Mode.RATE : Mode.HEALTHY;
			case OUTAGE -> elapsedNanos < outageNanos ? Mode.OUTAGE : Mode.HEALTHY;
			case EXHAUSTED -> Mode.EXHAUSTED;
			case HEALTHY -> Mode.HEALTHY;
		};
	}

	List<Attempt> attempts() {
		return List.copyOf(attempts);
	}

	int successCount() {
		return successes.get();
	}

	int failureCount() {
		return failures.get();
	}

	void reset() {
		attempts.clear();
		successes.set(0);
		failures.set(0);
		windowStartNanos = System.nanoTime();
	}

	/** 핸들러 진입 한 건 - 창 시작 이후 경과 나노초와 실패 여부. */
	record Attempt(long elapsedNanos, String aggregateId, boolean failed) {

		double elapsedMillis() {
			return elapsedNanos / 1_000_000d;
		}
	}
}
