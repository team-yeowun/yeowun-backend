package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import modi.backend.ingestionv2.common.deadletter.DeadLetter;
import modi.backend.ingestionv2.common.deadletter.DeadLetterStatus;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxAppended;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.support.error.CoreException;

@DisplayName("재주입")
@Import(DeadLetterRedriveTest.RedriveTransactionProbeConfig.class)
class DeadLetterRedriveTest extends DeliveryTestSupport {

	@Autowired private RedriveTransactionProbe transactionProbe;

	@Test
	@DisplayName("재주입하면 시점이 찍히고 새 PENDING 행이 적재된다")
	void 재주입하면_시점이_찍히고_새_PENDING_행이_적재된다() {
		// given 격리 행 하나
		long deadLetterId = isolateOne();

		// when
		IngestionDeliveryResult.Redriven redriven =
				ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId));

		// then 표시가 찍히고 새 행이 적재된다(기존 행을 되돌리지 않는다)
		assertThat(redriven.aggregateId()).isEqualTo(vendorKey);
		assertThat(deadLetterRepository.findById(deadLetterId).orElseThrow().getStatus()).isEqualTo(DeadLetterStatus.REPLAYED);
		Outbox appended = outboxRepository.findAll().getFirst();
		assertThat(appended.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(appended.getRetryCount()).isZero();
		assertThat(appended.getAggregateId()).isEqualTo(vendorKey);
		assertThat(transactionProbe.wasTransactionActive()).isTrue();
	}

	@Test
	@DisplayName("아웃박스 적재 경로가 실패하면 REPLAYED 표시도 함께 롤백된다")
	void 아웃박스_적재_실패는_REPLAYED_표시도_롤백한다() {
		// given 격리 행 하나와 OutboxAppended 발행 실패를 주입한다
		long deadLetterId = isolateOne();
		transactionProbe.failNextAppend();

		// when 재주입 트랜잭션의 마지막 단계가 실패한다
		assertThatThrownBy(() ->
				ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("재주입 아웃박스 실패 주입");

		// then DLQ만 닫히는 부분 성공 없이 둘 다 원래 상태다
		assertThat(deadLetterRepository.findById(deadLetterId).orElseThrow().getStatus())
				.isEqualTo(DeadLetterStatus.PENDING);
		assertThat(outboxRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("재주입 후 드레인하면 핸들러가 다시 실행된다")
	void 재주입_후_드레인하면_핸들러가_다시_실행된다() {
		// given
		long deadLetterId = isolateOne();
		ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId));

		// when
		drainAll();

		// then 회생 경로 전체가 이어진다
		assertThat(recordingCollectedHandler.received()).containsExactly(vendorKey);
		assertThat(outboxRepository.findAll())
				.allMatch(outbox -> outbox.getStatus() == OutboxStatus.SENT);
	}

	@Test
	@DisplayName("이미 재주입한 항목은 거절된다")
	void 이미_재주입한_항목은_거절된다() {
		// given 이미 재주입한 행
		long deadLetterId = isolateOne();
		ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId));

		// when & then 관리자가 목록을 두 번 훑어도 같은 사실이 여러 번 흐르지 않는다
		assertThatThrownBy(() -> ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId)))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(IngestionErrorCode.DEAD_LETTER_ALREADY_RESOLVED);
	}

	@Test
	@DisplayName("해석 불가 항목은 재주입되지 않는다")
	void 해석_불가_항목은_재주입되지_않는다() {
		// given 종류와 원천 키가 없는 격리 행
		deadLetterService.isolateMalformed(IngestionStream.DB.key(), "0-1", "{broken", new DeadLetter.Failure("해석 불가", null, DeadLetter.STEP_DECODE));
		long deadLetterId = deadLetterRepository.findAll().getFirst().getId();

		// when & then 널을 담은 아웃박스 행이 적재되어 발송 단계에서 터지는 일을 막는다
		assertThatThrownBy(() -> ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId)))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(IngestionErrorCode.DEAD_LETTER_NOT_REDRIVABLE);
		assertThat(outboxRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("무시하면 IGNORED로 표시되어 목록에서 빠지고 아웃박스에는 아무것도 적재되지 않는다")
	void 무시하면_IGNORED로_표시되어_목록에서_빠진다() {
		// given 격리 행 하나
		long deadLetterId = isolateOne();

		// when
		IngestionDeliveryResult.Ignored ignored =
				ingestionDeliveryFacade.ignore(IngestionDeliveryCriteria.Ignore.of(deadLetterId));

		// then 기록은 남되 관리자 목록과 아웃박스에는 나타나지 않는다
		assertThat(ignored.status()).isEqualTo(DeadLetterStatus.IGNORED.name());
		assertThat(deadLetterRepository.findById(deadLetterId).orElseThrow().getResolvedAt()).isNotNull();
		assertThat(ingestionDeliveryFacade.findDeadLetters(IngestionDeliveryCriteria.Listing.of(50)).count()).isZero();
		assertThat(outboxRepository.findAll()).isEmpty();
		assertThatThrownBy(() -> ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId)))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(IngestionErrorCode.DEAD_LETTER_ALREADY_RESOLVED);
	}

	@Test
	@DisplayName("없는 식별자는 찾을 수 없다는 오류다")
	void 없는_식별자는_찾을_수_없다는_오류다() {
		assertThatThrownBy(() -> ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(999_999L)))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(IngestionErrorCode.DEAD_LETTER_NOT_FOUND);
	}

	private long isolateOne() {
		deadLetterService.isolate(OutboxPayload.of(IngestionEventType.COLLECTED, vendorKey, IngestionClock.now()), IngestionStream.DB.key(), "0-1", new DeadLetter.Failure("원인", null, "test"), 3);
		DeadLetter isolated = deadLetterRepository.findAll().getFirst();
		return isolated.getId();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RedriveTransactionProbeConfig {

		@Bean
		RedriveTransactionProbe redriveTransactionProbe() {
			return new RedriveTransactionProbe();
		}
	}

	static class RedriveTransactionProbe {

		private final AtomicBoolean failNext = new AtomicBoolean();
		private volatile boolean transactionActive;

		@EventListener
		public void on(OutboxAppended ignored) {
			transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
			if (failNext.getAndSet(false)) {
				throw new IllegalStateException("재주입 아웃박스 실패 주입");
			}
		}

		void failNextAppend() {
			failNext.set(true);
		}

		boolean wasTransactionActive() {
			return transactionActive;
		}
	}
}
