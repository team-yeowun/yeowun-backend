package modi.backend.ingestion.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.properties.OutboxProperties;

/**
 * 소비 수명주기({@code consume}) 순수 단위 검증(mock repo) — 선별→스텝 실행→{@link StepResult}대로 전이가
 * 아웃박스 메커니즘의 책임으로 이동한 계약을 못박는다: SUCCESS→markSucceeded, FAIL→markFailed(도메인 정책),
 * 낙관락 충돌→무전이·집계 제외, onPermanentFailure 콜백은 FAILED_PERMANENT로 굳었을 때만.
 */
class ExhibitionOutboxServiceTest {

	private OutboxMessageRepository repository;
	private ExhibitionOutboxService service;

	@BeforeEach
	void setUp() {
		repository = mock(OutboxMessageRepository.class);
		service = new ExhibitionOutboxService(repository, new OutboxProperties(3, 60L, 3600L, 50, null, null, null));
	}

	private OutboxMessage due(IngestionEventType type, String key) {
		OutboxMessage message = OutboxMessage.enqueue(type, key, LocalDateTime.now());
		given(repository.findDue(any(), any(), anyInt())).willReturn(List.of(message));
		return message;
	}

	@Test
	@DisplayName("consume — 스텝이 SUCCESS면 성공 전이(markSucceeded)한다")
	void 성공전이() {
		OutboxMessage message = due(IngestionEventType.DRAFT_STAGED, "E1");

		service.consume(IngestionEventType.DRAFT_STAGED, m -> StepResult.success());
		assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		verify(repository).save(message);
	}

	@Test
	@DisplayName("consume — 스텝이 FAIL이면 실패 전이(markFailed — 백오프·소진 승격은 도메인 정책)한다")
	void 실패전이() {
		OutboxMessage message = due(IngestionEventType.DRAFT_STAGED, "E1");

		service.consume(IngestionEventType.DRAFT_STAGED,
				m -> StepResult.fail(OutboxFailureType.RETRYABLE, "timeout"));
		assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE);
		assertThat(message.getLastError()).isEqualTo("timeout");
		verify(repository).save(message);
	}

	@Test
	@DisplayName("consume — 전이 저장의 낙관락 충돌은 다른 워커 선점 = 무전이 skip으로 삼킨다(그 워커가 마감한다)")
	void 낙관락_무전이() {
		OutboxMessage message = due(IngestionEventType.DRAFT_STAGED, "E1");
		given(repository.save(any())).willThrow(new OptimisticLockingFailureException("version"));

		// 예외를 삼킨다 — 선점당한 건 정상 경로이므로 소비 배치가 통째로 죽으면 안 된다.
		assertThatCode(() -> service.consume(IngestionEventType.DRAFT_STAGED, m -> StepResult.success()))
				.doesNotThrowAnyException();
		// 저장이 튕겼으니 이 워커가 본 상태는 커밋되지 않는다 — 마감은 이긴 워커 몫이다.
		assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED); // 인메모리 전이는 됐으나 미저장
	}

	@Test
	@DisplayName("consume — 스텝이 SKIP(낙관락 등 스텝 내 선점)이면 상태 전이 자체를 하지 않는다")
	void 스텝skip_무전이() {
		OutboxMessage message = due(IngestionEventType.DRAFT_STAGED, "E1");

		service.consume(IngestionEventType.DRAFT_STAGED, m -> StepResult.skip());
		assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("consume — PERMANENT로 굳었을 때만 onPermanentFailure 콜백을 부른다(RETRYABLE 실패는 부르지 않는다)")
	void 영구실패_콜백() {
		OutboxMessage message = due(IngestionEventType.DRAFT_STAGED, "E1");
		AtomicReference<OutboxMessage> permanent = new AtomicReference<>();

		service.consume(IngestionEventType.DRAFT_STAGED,
				m -> StepResult.fail(OutboxFailureType.RETRYABLE, "timeout"), permanent::set);
		assertThat(permanent.get()).isNull(); // RETRYABLE — 아직 굳지 않았다

		service.consume(IngestionEventType.DRAFT_STAGED,
				m -> StepResult.fail(OutboxFailureType.PERMANENT, "404"), permanent::set);
		assertThat(permanent.get()).isSameAs(message); // FAILED_PERMANENT로 굳음 — draft 가시화 연동 지점
		assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_PERMANENT);
	}
}
