package modi.backend.ingestion.domain.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OutboxMessage 도메인 단위 검증 — 상태 전이(RETRYABLE 백오프·최대 초과 승격), enqueue 초기값, 재활성화, 선별 판정.
 * 순수 단위(컨텍스트 없음) — 상태 변경 규칙이 전부 Entity 안에 있음을 확인한다.
 */
class OutboxMessageTest {

	private static final RetryPolicy POLICY = new RetryPolicy(3, 60, 3600);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 17, 12, 0);

	@Test
	@DisplayName("enqueue — PENDING·시도 0·즉시 도래(now)로 시작한다")
	void enqueue_초기값() {
		OutboxMessage job = OutboxMessage.enqueue(IngestionEventType.DRAFT_STAGED, "EXT-1", NOW);

		assertThat(job.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);
		assertThat(job.getAttemptCount()).isZero();
		assertThat(job.getNextAttemptAt()).isEqualTo(NOW);
		assertThat(job.isDue(NOW)).isTrue();
		assertThat(job.isTerminal()).isFalse();
	}

	@Test
	@DisplayName("succeed — SUCCEEDED 종료·완료시각 기록·원인 제거")
	void succeed_종료() {
		OutboxMessage job = OutboxMessage.enqueue(IngestionEventType.DETAIL_FETCHED, "EXT-1", NOW);

		job.succeed(NOW.plusMinutes(1));

		assertThat(job.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		assertThat(job.isTerminal()).isTrue();
		assertThat(job.getCompletedAt()).isEqualTo(NOW.plusMinutes(1));
		assertThat(job.getLastError()).isNull();
		assertThat(job.isDue(NOW.plusYears(1))).isFalse(); // 종료 작업은 선별되지 않는다
	}

	@Test
	@DisplayName("recordFailure(RETRYABLE) — 시도++·FAILED_RETRYABLE·지수 백오프로 next_attempt_at을 민다")
	void 실패_재시도_지수백오프() {
		OutboxMessage job = OutboxMessage.enqueue(IngestionEventType.DRAFT_STAGED, "EXT-1", NOW);

		job.recordFailure(OutboxFailureType.RETRYABLE, "timeout", POLICY, NOW);

		assertThat(job.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE);
		assertThat(job.getAttemptCount()).isEqualTo(1);
		assertThat(job.getLastError()).isEqualTo("timeout");
		// attemptCount=1 → base(60초) 뒤 재시도.
		assertThat(job.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));

		job.recordFailure(OutboxFailureType.RETRYABLE, "timeout again", POLICY, NOW);
		assertThat(job.getAttemptCount()).isEqualTo(2);
		// attemptCount=2 → base×2^1 = 120초.
		assertThat(job.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(120));
	}

	@Test
	@DisplayName("recordFailure(RETRYABLE) — 최대 시도를 넘기면 FAILED_PERMANENT로 승격한다(무한 재시도 방지)")
	void 실패_최대초과_영구승격() {
		OutboxMessage job = OutboxMessage.enqueue(IngestionEventType.DRAFT_STAGED, "EXT-1", NOW);

		job.recordFailure(OutboxFailureType.RETRYABLE, "e1", POLICY, NOW); // 1
		job.recordFailure(OutboxFailureType.RETRYABLE, "e2", POLICY, NOW); // 2
		assertThat(job.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE);

		job.recordFailure(OutboxFailureType.RETRYABLE, "e3", POLICY, NOW); // 3 = maxAttempts → 승격

		assertThat(job.getAttemptCount()).isEqualTo(3);
		assertThat(job.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_PERMANENT);
		assertThat(job.isTerminal()).isTrue();
		assertThat(job.getNextAttemptAt()).isNull();
		assertThat(job.getCompletedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("recordFailure(PERMANENT) — 4xx·파싱실패는 시도와 무관하게 즉시 FAILED_PERMANENT")
	void 실패_영구_즉시() {
		OutboxMessage job = OutboxMessage.enqueue(IngestionEventType.DRAFT_STAGED, "EXT-1", NOW);

		job.recordFailure(OutboxFailureType.PERMANENT, "400 Bad Request", POLICY, NOW);

		assertThat(job.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_PERMANENT);
		assertThat(job.getAttemptCount()).isEqualTo(1);
		assertThat(job.getLastError()).isEqualTo("400 Bad Request");
		assertThat(job.getNextAttemptAt()).isNull();
	}

	@Test
	@DisplayName("reactivate — 종료된 작업만 되살린다(PENDING·시도 리셋). 미종료면 no-op")
	void reactivate_종료만() {
		OutboxMessage terminal = OutboxMessage.enqueue(IngestionEventType.PLACE_STAGED, "PLACE-1", NOW);
		terminal.succeed(NOW);

		terminal.reactivate(NOW.plusDays(40));

		assertThat(terminal.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);
		assertThat(terminal.getAttemptCount()).isZero();
		assertThat(terminal.getNextAttemptAt()).isEqualTo(NOW.plusDays(40));
		assertThat(terminal.getCompletedAt()).isNull();

		OutboxMessage pending = OutboxMessage.enqueue(IngestionEventType.PLACE_STAGED, "PLACE-2", NOW);
		pending.reactivate(NOW.plusDays(1));
		assertThat(pending.getNextAttemptAt()).isEqualTo(NOW); // no-op: 이미 선별 대상
	}

	@Test
	@DisplayName("isDue — 미종료이고 next_attempt_at이 도래했을 때만 참")
	void isDue_판정() {
		OutboxMessage job = OutboxMessage.enqueue(IngestionEventType.DRAFT_STAGED, "EXT-1", NOW);
		job.recordFailure(OutboxFailureType.RETRYABLE, "e", POLICY, NOW); // next = NOW+60s

		assertThat(job.isDue(NOW.plusSeconds(59))).isFalse(); // 아직 도래 전
		assertThat(job.isDue(NOW.plusSeconds(60))).isTrue(); // 도래
		assertThat(job.isDue(NOW.plusSeconds(120))).isTrue();
	}
}
