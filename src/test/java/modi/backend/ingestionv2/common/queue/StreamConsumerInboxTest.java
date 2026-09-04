package modi.backend.ingestionv2.common.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.deadletter.DeadLetterService;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.inbox.InboxClaim;
import modi.backend.ingestionv2.common.inbox.InboxService;
import modi.backend.support.error.CoreException;

@DisplayName("StreamConsumer Inbox 분기")
class StreamConsumerInboxTest {

	private DeadLetterService deadLetterService;
	private InboxService inboxService;
	private StreamOperations<String, String, String> streamOperations;
	private RecordingHandler handler;
	private StreamConsumer consumer;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		deadLetterService = mock(DeadLetterService.class);
		inboxService = mock(InboxService.class);
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		streamOperations = mock(StreamOperations.class);
		IngestionProperties properties = mock(IngestionProperties.class);
		handler = new RecordingHandler();
		when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
		when(properties.consumerGroup()).thenReturn("ingestion-v2");
		consumer = new StreamConsumer(
				new IngestionEventRouter(List.of(handler)), deadLetterService, inboxService,
				redisTemplate, properties, new SimpleMeterRegistry());
	}

	@Test
	@DisplayName("종결된 eventId는 handler 없이 ack한다")
	void terminal은_handler_없이_ack한다() {
		OutboxPayload payload = OutboxPayload.of(IngestionEventType.COLLECTED, "EXH-1", IngestionClock.now());
		MapRecord<String, String, String> message = message(payload);
		when(inboxService.claim("ingestion-v2", payload.eventId()))
				.thenReturn(InboxClaim.terminal("ingestion-v2", payload.eventId()));

		consumer.onMessage(message);

		assertThat(handler.calls).isZero();
		verify(streamOperations).acknowledge("ingestion-v2", message);
	}

	@Test
	@DisplayName("처리 중인 eventId는 handler도 ack도 하지 않는다")
	void processing은_handler도_ack도_하지_않는다() {
		OutboxPayload payload = OutboxPayload.of(IngestionEventType.COLLECTED, "EXH-1", IngestionClock.now());
		MapRecord<String, String, String> message = message(payload);
		when(inboxService.claim("ingestion-v2", payload.eventId()))
				.thenReturn(InboxClaim.inProgress("ingestion-v2", payload.eventId()));

		consumer.onMessage(message);

		assertThat(handler.calls).isZero();
		verify(streamOperations, never()).acknowledge("ingestion-v2", message);
	}

	@Test
	@DisplayName("처리권을 얻은 eventId는 handler와 Inbox 종결 뒤 ack한다")
	void acquired는_성공_종결_뒤_ack한다() {
		OutboxPayload payload = OutboxPayload.of(IngestionEventType.COLLECTED, "EXH-1", IngestionClock.now());
		MapRecord<String, String, String> message = message(payload);
		InboxClaim claim = InboxClaim.acquired(
				"ingestion-v2", payload.eventId(), "123e4567-e89b-12d3-a456-426614174001");
		when(inboxService.claim("ingestion-v2", payload.eventId())).thenReturn(claim);
		when(inboxService.succeed(claim)).thenReturn(true);

		consumer.onMessage(message);

		assertThat(handler.calls).isEqualTo(1);
		verify(inboxService).succeed(claim);
		verify(streamOperations).acknowledge("ingestion-v2", message);
	}

	@Test
	@DisplayName("Inbox 종결권을 잃으면 handler 결과를 ack하지 않는다")
	void 종결권을_잃으면_ack하지_않는다() {
		OutboxPayload payload = OutboxPayload.of(IngestionEventType.COLLECTED, "EXH-1", IngestionClock.now());
		MapRecord<String, String, String> message = message(payload);
		InboxClaim claim = InboxClaim.acquired(
				"ingestion-v2", payload.eventId(), "123e4567-e89b-12d3-a456-426614174001");
		when(inboxService.claim("ingestion-v2", payload.eventId())).thenReturn(claim);
		when(inboxService.succeed(claim)).thenReturn(false);

		consumer.onMessage(message);

		assertThat(handler.calls).isEqualTo(1);
		verify(streamOperations, never()).acknowledge("ingestion-v2", message);
	}

	@Test
	@DisplayName("handler 실패는 Inbox를 FAILED로 열고 ack하지 않는다")
	void handler_실패는_Inbox를_열고_ack하지_않는다() {
		OutboxPayload payload = OutboxPayload.of(IngestionEventType.COLLECTED, "EXH-1", IngestionClock.now());
		MapRecord<String, String, String> message = message(payload);
		InboxClaim claim = InboxClaim.acquired(
				"ingestion-v2", payload.eventId(), "123e4567-e89b-12d3-a456-426614174001");
		CoreException failure = new CoreException(IngestionErrorCode.STREAM_PUBLISH_FAILED, "일시 장애");
		handler.failure = failure;
		when(inboxService.claim("ingestion-v2", payload.eventId())).thenReturn(claim);

		consumer.onMessage(message);

		verify(inboxService).fail(claim, failure);
		verify(streamOperations, never()).acknowledge("ingestion-v2", message);
	}

	@Test
	@DisplayName("구버전 eventId 없는 payload는 Inbox를 우회해 처리한다")
	void legacy_payload는_Inbox를_우회한다() {
		OutboxPayload legacy = OutboxPayload.fromJson("""
				{"aggregateType":"COLLECTION","aggregateId":"legacy-1",\
				"eventType":"COLLECTED","occurredAt":"2026-08-30T15:04:05.123456"}
				""");
		MapRecord<String, String, String> message = message(legacy);

		consumer.onMessage(message);

		assertThat(handler.calls).isEqualTo(1);
		verifyNoInteractions(inboxService);
		verify(streamOperations).acknowledge("ingestion-v2", message);
	}

	@SuppressWarnings("unchecked")
	private MapRecord<String, String, String> message(OutboxPayload payload) {
		MapRecord<String, String, String> message = mock(MapRecord.class);
		when(message.getValue()).thenReturn(EventRecord.of(payload).toFields());
		when(message.getStream()).thenReturn(IngestionStream.DB.key());
		when(message.getId()).thenReturn(RecordId.of("1-0"));
		return message;
	}

	private static final class RecordingHandler implements IngestionEventHandler {
		private int calls;
		private RuntimeException failure;

		@Override
		public boolean supports(IngestionEventType type) {
			return type == IngestionEventType.COLLECTED;
		}

		@Override
		public void handle(String vendorKey) {
			calls++;
			if (failure != null) {
				throw failure;
			}
		}
	}
}
