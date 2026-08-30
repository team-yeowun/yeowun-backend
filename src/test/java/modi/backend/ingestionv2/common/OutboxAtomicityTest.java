package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.deadletter.DeadLetter;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxStatus;

@DisplayName("아웃박스 적재 원자성")
class OutboxAtomicityTest extends DeliveryTestSupport {

	@Test
	@DisplayName("적재가 도메인 트랜잭션과 함께 커밋된다")
	void 적재가_도메인_트랜잭션과_함께_커밋된다() {
		// given 같은 트랜잭션 안에서 기록 하나와 사실 하나를 함께 쓴다
		// when 정상 종료한다
		transactionTemplate.executeWithoutResult(status -> {
			deadLetterService.isolate(OutboxPayload.of(IngestionEventType.COLLECTED, vendorKey, IngestionClock.now()), "ingestion.db", "0-1", new DeadLetter.Failure("원인", null, "test"), 3);
			outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		});

		// then 두 행이 모두 남는다
		assertThat(outboxRepository.findAll()).hasSize(1);
		assertThat(deadLetterRepository.findAll()).hasSize(1);
	}

	@Test
	@DisplayName("트랜잭션이 롤백되면 아웃박스 행도 함께 사라진다")
	void 트랜잭션이_롤백되면_아웃박스_행도_사라진다() {
		// given 같은 트랜잭션 안에서 기록 하나와 사실 하나를 함께 쓴다
		// when 그 트랜잭션을 예외로 되돌린다
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			deadLetterService.isolate(OutboxPayload.of(IngestionEventType.COLLECTED, vendorKey, IngestionClock.now()), "ingestion.db", "0-1", new DeadLetter.Failure("원인", null, "test"), 3);
			outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
			throw new IllegalStateException("도메인 반영 실패");
		})).isInstanceOf(IllegalStateException.class);

		// then 두 행이 모두 남지 않는다
		assertThat(outboxRepository.findAll()).isEmpty();
		assertThat(deadLetterRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("트랜잭션 밖 단독 적재도 스스로 커밋된다")
	void 트랜잭션_밖_단독_적재도_스스로_커밋된다() {
		// given 트랜잭션이 없는 상태에서
		// when 적재만 부른다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// then PENDING 행 하나가 남는다 (관리자 재주입이 이 경로를 쓴다)
		assertThat(outboxRepository.findAll())
				.singleElement()
				.extracting(Outbox::getStatus)
				.isEqualTo(OutboxStatus.PENDING);
	}

	@Test
	@DisplayName("같은 사실을 두 번 적재하면 행이 두 개 생긴다")
	void 같은_사실을_두_번_적재하면_행이_두_개_생긴다() {
		// given 같은 종류와 원천 키로
		// when 두 번 적재한다
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);

		// then 행이 둘이고 둘 다 PENDING 이다 (유일 제약을 두지 않은 판단이 유지된다)
		assertThat(outboxRepository.findAll())
				.hasSize(2)
				.allMatch(outbox -> outbox.getStatus() == OutboxStatus.PENDING);
	}
}
