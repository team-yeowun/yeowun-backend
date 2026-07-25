package modi.backend.ingestion.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;

/**
 * 발행 컴포넌트 단위 — 멱등 enqueue(종료 비부활)·재sync 안전망(enqueueOrReactivate)·커밋 직후 적재 알림 발행을 못박는다.
 * 구 ExhibitionOutboxService의 발행부 분리(의존 규칙 §1-1 — 서비스는 이 컴포넌트만 주입).
 */
class OutboxPublisherTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 1, 0);

	private OutboxMessageRepository repository;
	private ApplicationEventPublisher eventPublisher;
	private OutboxPublisher publisher;

	@BeforeEach
	void setUp() {
		repository = mock(OutboxMessageRepository.class);
		eventPublisher = mock(ApplicationEventPublisher.class);
		publisher = new OutboxPublisher(repository, eventPublisher);
	}

	@Test
	@DisplayName("enqueue — 신규면 PENDING 행 저장 + 적재 알림 발행, 대상 키가 비면 no-op")
	void enqueue_new() {
		given(repository.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, "EXT-1"))
				.willReturn(Optional.empty());

		publisher.enqueue(IngestionEventType.DRAFT_STAGED, "EXT-1", NOW);
		publisher.enqueue(IngestionEventType.PLACE_STAGED, null, NOW);   // place 없는 전시 — 발행 자체가 없다
		publisher.enqueue(IngestionEventType.PLACE_STAGED, " ", NOW);

		then(repository).should(times(1)).save(any(OutboxMessage.class));
		then(eventPublisher).should(times(1)).publishEvent(any(OutboxEnqueued.class));
	}

	@Test
	@DisplayName("enqueue — 이미 행이 있으면(종료 포함) 되살리지 않는다(모든 이벤트가 대상당 한 번 — D4 이후 반복 이벤트 없음)")
	void enqueue_idempotent_no_revive() {
		OutboxMessage done = OutboxMessage.enqueue(IngestionEventType.PLACE_STAGED, "장소", NOW);
		done.succeed(NOW);
		given(repository.findByMessageTypeAndTargetKey(IngestionEventType.PLACE_STAGED, "장소"))
				.willReturn(Optional.of(done));

		publisher.enqueue(IngestionEventType.PLACE_STAGED, "장소", NOW);

		then(repository).should(never()).save(any());
		assertThat(done.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("enqueueOrReactivate — 종료 메시지를 부활시킨다(재sync 안전망·관리자 수동 재시도 공용 경로)")
	void reactivate_terminal_message() {
		OutboxMessage dead = OutboxMessage.enqueue(IngestionEventType.DETAIL_FETCHED, "EXT-1", NOW);
		dead.recordFailure(modi.backend.ingestion.domain.outbox.OutboxFailureType.PERMANENT, "4xx",
				new modi.backend.ingestion.domain.outbox.RetryPolicy(3, 60, 3600), NOW);
		given(repository.findByMessageTypeAndTargetKey(IngestionEventType.DETAIL_FETCHED, "EXT-1"))
				.willReturn(Optional.of(dead));

		publisher.enqueueOrReactivate(IngestionEventType.DETAIL_FETCHED, "EXT-1", NOW.plusDays(1));

		assertThat(dead.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);
		assertThat(dead.getAttemptCount()).isZero(); // 새 시도로 취급
		then(repository).should().save(dead);
		then(eventPublisher).should().publishEvent(any(OutboxEnqueued.class)); // 적재 알림 — 릴레이 즉시 소비
	}
}
