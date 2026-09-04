package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.inbox.InboxJpaRepository;
import modi.backend.ingestionv2.common.inbox.InboxClaim;
import modi.backend.ingestionv2.common.inbox.InboxService;
import modi.backend.ingestionv2.common.inbox.InboxStatus;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.queue.EventDispatcher;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.support.error.CoreException;

@DisplayName("Inbox 멱등 소비")
class InboxIdempotencyIntegrationTest extends DeliveryTestSupport {

	@Autowired private InboxJpaRepository inboxRepository;
	@Autowired private InboxService inboxService;
	@Autowired private EventDispatcher eventDispatcher;

	@Test
	@DisplayName("같은 eventId가 두 Redis 레코드로 전달돼도 handler는 한 번만 실행한다")
	void 같은_eventId의_중복_레코드는_한_번만_처리한다() {
		// given XADD 성공/SENT 커밋 실패처럼 같은 Outbox payload를 두 번 발행한다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		Outbox outbox = outboxRepository.findAll().getFirst();
		outboxDispatcher.dispatchPending();
		eventDispatcher.dispatch(outbox.toPayload());
		List<MapRecord<String, String, String>> duplicates = readAs("duplicate-consumer");
		assertThat(duplicates).hasSize(2);

		duplicates.forEach(streamConsumer::onMessage);

		assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey);
		assertThat(inboxRepository.findAll()).singleElement().satisfies(inbox -> {
			assertThat(inbox.getEventId()).isEqualTo(outbox.getEventId());
			assertThat(inbox.getStatus()).isEqualTo(InboxStatus.SUCCEEDED);
		});
		assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("처리 중인 중복은 ack하지 않고 원 실행이 끝난 뒤 종결 상태로 확인한다")
	void 처리중인_중복은_원_실행의_복구_기회를_지우지_않도록_ack하지_않는다() throws Exception {
		OutboxPayload payload = OutboxPayload.of(IngestionEventType.COLLECTED, vendorKey, IngestionClock.now());
		eventDispatcher.dispatch(payload);
		eventDispatcher.dispatch(payload);
		List<MapRecord<String, String, String>> duplicates = readAs("concurrent-consumer");
		assertThat(duplicates).hasSize(2);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		recordingCollectedHandler.behaveWith(key -> {
			entered.countDown();
			await(release);
		});

		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> first = worker.submit(() -> streamConsumer.onMessage(duplicates.getFirst()));
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

			streamConsumer.onMessage(duplicates.get(1));

			assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey);
			assertThat(pendingOf(IngestionStream.DB).size()).isEqualTo(2);

			release.countDown();
			first.get(10, TimeUnit.SECONDS);
			assertThat(pendingOf(IngestionStream.DB).size()).isEqualTo(1);

			streamConsumer.onMessage(duplicates.get(1));

			assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey);
			assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
		} finally {
			release.countDown();
			worker.shutdownNow();
		}
	}

	@Test
	@DisplayName("handler 실패는 Inbox를 FAILED로 열어 두고 같은 이벤트의 재시도를 허용한다")
	void 실패한_이벤트는_같은_eventId로_재시도한다() {
		AtomicInteger attempts = new AtomicInteger();
		recordingCollectedHandler.behaveWith(key -> {
			if (attempts.incrementAndGet() == 1) {
				throw new CoreException(IngestionErrorCode.STREAM_PUBLISH_FAILED, "일시 장애");
			}
		});
		OutboxPayload payload = OutboxPayload.of(IngestionEventType.COLLECTED, vendorKey, IngestionClock.now());
		eventDispatcher.dispatch(payload);
		MapRecord<String, String, String> message = readAs("retry-consumer").getFirst();

		streamConsumer.onMessage(message);
		assertThat(inboxRepository.findAll().getFirst().getStatus()).isEqualTo(InboxStatus.FAILED);
		streamConsumer.onMessage(message);

		assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey, vendorKey);
		assertThat(inboxRepository.findAll().getFirst().getStatus()).isEqualTo(InboxStatus.SUCCEEDED);
		assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("보존 기간이 지난 종결 Inbox만 정리하고 실패·처리 중 상태는 남긴다")
	void 종결_Inbox만_유한_보존한다() {
		String succeededId = OutboxPayload.of(
				IngestionEventType.COLLECTED, vendorKey + "-done", IngestionClock.now()).eventId();
		String failedId = OutboxPayload.of(
				IngestionEventType.COLLECTED, vendorKey + "-failed", IngestionClock.now()).eventId();
		InboxClaim succeeded = inboxService.claim("ingestion-v2", succeededId);
		InboxClaim failed = inboxService.claim("ingestion-v2", failedId);
		assertThat(inboxService.succeed(succeeded)).isTrue();
		assertThat(inboxService.fail(failed, new IllegalStateException("재시도 대상"))).isTrue();

		int deleted = inboxService.cleanupTerminal(IngestionClock.now().plusSeconds(1), 500);

		assertThat(deleted).isEqualTo(1);
		assertThat(inboxRepository.findAll()).singleElement().satisfies(inbox -> {
			assertThat(inbox.getEventId()).isEqualTo(failedId);
			assertThat(inbox.getStatus()).isEqualTo(InboxStatus.FAILED);
		});
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("handler 관문이 시간 안에 열리지 않았습니다.");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("handler 대기가 중단됐습니다.", interrupted);
		}
	}
}
