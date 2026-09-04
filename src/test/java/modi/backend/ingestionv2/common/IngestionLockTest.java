package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.common.lock.IngestionJobLock;
import modi.backend.ingestionv2.common.lock.RedisMarkerLock;

/** 중복 실행 판정의 원시 연산과 잡 단위 락의 계약을 고정한다. */
class IngestionLockTest extends IngestionTestSupport {

	@Autowired private RedisMarkerLock markerLock;
	@Autowired private IngestionJobLock jobLock;

	@Test
	@DisplayName("같은 키는 한 번만 잡힌다")
	void 같은_키는_한_번만_잡힌다() {
		// given
		String key = "outbox:test:" + System.nanoTime();

		// when
		boolean first = markerLock.tryAcquire(key, "app1", Duration.ofSeconds(30));
		boolean second = markerLock.tryAcquire(key, "app2", Duration.ofSeconds(30));

		// then
		assertThat(first).isTrue();
		assertThat(second).isFalse();
	}

	@Test
	@DisplayName("만료된 키는 다시 잡힌다")
	void 만료된_키는_다시_잡힌다() {
		// given
		String key = "outbox:test:" + System.nanoTime();
		assertThat(markerLock.tryAcquire(key, "app1", Duration.ofMillis(300))).isTrue();

		// when·then 만료를 기다린 뒤에는 다른 소유자가 잡는다
		assertThat(acquireAfterExpiry(key, "app2")).isTrue();
	}

	@Test
	@DisplayName("만료 뒤 다른 소유자가 잡은 락을 이전 소유자의 해제가 지우지 않는다")
	void 이전_소유자의_해제가_새_락을_지우지_않는다() {
		// given 앞 소유자의 락이 만료되고 뒤 소유자가 같은 키를 잡았다
		String key = "lock:test:" + System.nanoTime();
		assertThat(markerLock.tryAcquire(key, "app1", Duration.ofMillis(300))).isTrue();
		assertThat(acquireAfterExpiry(key, "app2")).isTrue();

		// when 뒤늦게 앞 소유자가 해제를 시도한다
		boolean released = markerLock.release(key, "app1");

		// then 지워지지 않고 뒤 소유자의 락이 그대로 살아 있다
		assertThat(released).isFalse();
		assertThat(markerLock.tryAcquire(key, "app3", Duration.ofSeconds(30))).isFalse();
	}

	@Test
	@DisplayName("소유자가 해제하면 다음 주기가 곧바로 잡는다")
	void 소유자가_해제하면_다음_주기가_잡는다() {
		// given
		String key = "lock:test:" + System.nanoTime();
		assertThat(markerLock.tryAcquire(key, "app1", Duration.ofSeconds(30))).isTrue();

		// when
		boolean released = markerLock.release(key, "app1");

		// then
		assertThat(released).isTrue();
		assertThat(markerLock.tryAcquire(key, "app2", Duration.ofSeconds(30))).isTrue();
	}

	@Test
	@DisplayName("잡 단위 락은 동시에 깨어난 둘 중 한 쪽만 돌린다")
	void 잡_단위_락은_한_쪽만_돌린다() throws Exception {
		// given
		String job = "test-job-" + System.nanoTime();
		AtomicInteger executions = new AtomicInteger();
		CyclicBarrier bothInside = new CyclicBarrier(2);

		// when 둘이 같은 순간에 같은 잡을 민다
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			Callable<Void> attempt = () -> {
				await(bothInside);
				jobLock.runIfAcquired(job, () -> {
					executions.incrementAndGet();
					sleepBriefly();
				});
				return null;
			};
			Future<Void> first = workers.submit(attempt);
			Future<Void> second = workers.submit(attempt);
			first.get(30, TimeUnit.SECONDS);
			second.get(30, TimeUnit.SECONDS);
		} finally {
			workers.shutdownNow();
		}

		// then
		assertThat(executions.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("잡별 TTL 을 준 락은 그 시간이 지나면 다른 인스턴스가 이어받는다")
	void 잡별_TTL_락은_만료_뒤_이어받는다() {
		// given 소유자가 해제하지 않고 죽은 것처럼 락만 남긴다
		String job = "ttl-job-" + System.nanoTime();
		assertThat(markerLock.tryAcquire("lock:" + job, "dead-app", Duration.ofMillis(300))).isTrue();
		AtomicInteger executions = new AtomicInteger();

		// when 곧바로는 건너뛰고, TTL 이 지나면 돈다
		boolean immediate = jobLock.runIfAcquired(job, Duration.ofSeconds(5), executions::incrementAndGet);
		boolean afterExpiry = Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
				.until(() -> jobLock.runIfAcquired(job, Duration.ofSeconds(5), executions::incrementAndGet), ran -> ran);

		// then
		assertThat(immediate).isFalse();
		assertThat(afterExpiry).isTrue();
		assertThat(executions.get()).isEqualTo(1);
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(30, TimeUnit.SECONDS);
		} catch (Exception interrupted) {
			throw new IllegalStateException("두 호출이 동시에 열리지 않았습니다.", interrupted);
		}
	}

	/** TTL 이 지나기를 기다렸다가 다른 소유자로 잡는다. */
	private boolean acquireAfterExpiry(String key, String owner) {
		return Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
				.until(() -> markerLock.tryAcquire(key, owner, Duration.ofSeconds(30)), acquired -> acquired);
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(500);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
