package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.OutboxClaimStrategy;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;

/**
 * 운영 기본값(SKIP LOCKED)에서 두 발송기가 같은 미발행 행을 동시에 집었을 때의 결과를 고정한다.
 *
 * <ul>
 *   <li>스레드 둘의 실경합 - 인스턴스 두 대가 아니라 같은 컨텍스트의 스레드 둘이므로 "2대"라고 부르지 않는다</li>
 *   <li>여기서 재현되는 것은 같은 행에 대한 판정 경합 하나</li>
 * </ul>
 */
class OutboxConcurrentDispatchTest extends IngestionTestSupport {

	private static final int ROWS = 20;

	@Test
	@DisplayName("두 발송기가 동시에 집어도 이벤트는 행 수만큼만 나간다")
	void 두_발송기가_동시에_집어도_중복이_없다() throws Exception {
		// given 미발행 행 스무 개
		assertThat(properties.claimStrategy()).isEqualTo(OutboxClaimStrategy.SKIP_LOCKED);
		for (int index = 0; index < ROWS; index++) {
			outboxAppender.append(IngestionEventType.COLLECTED, vendorKey + "-" + index);
		}

		// when 두 스레드가 같은 순간에 발송 틱을 민다
		dispatchConcurrently();

		// then 대기열에 실린 항목도, 발행 완료 행도 정확히 행 수만큼
		assertThat(lengthOf(IngestionStream.DB)).isEqualTo(ROWS);
		assertThat(outboxRepository.countByStatus(OutboxStatus.SENT)).isEqualTo(ROWS);
		assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isZero();
		assertThat(redisTemplate.keys("outbox:*")).isEmpty();
	}

	private void dispatchConcurrently() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			for (int worker = 0; worker < 2; worker++) {
				workers.submit(() -> {
					try {
						start.await();
						// 각 발송기는 SKIP LOCKED로 다른 행을 집고, 남은 행이 없을 때까지 소진한다.
						while (outboxDispatcher.dispatchBatch().drainable()) {
							continue;
						}
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		} finally {
			workers.shutdownNow();
		}
	}
}
