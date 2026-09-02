package modi.backend.ingestionv2.common.outbox;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.support.error.CoreException;

/**
 * 아웃박스 행의 단일 창구.
 *
 * <ul>
 *   <li>append는 REQUIRED - 도메인 반영 트랜잭션에 합류해 함께 커밋. 적재 사실 이벤트를 함께 발행해 커밋 직후 발송 깨우기의 입력이 된다</li>
 *   <li>발송 트랜잭션 전용 네 건은 MANDATORY - 단독 호출되면 선점 잠금이 즉시 풀려 다른 인스턴스가 같은 행을 집음</li>
 *   <li>관리자 재시도와 정리는 REQUIRED - 호출 스레드에 트랜잭션이 없어 이 메서드가 경계를 소유</li>
 *   <li>상태 전이 판단은 전부 Outbox 엔티티 메서드에 위임</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OutboxService implements OutboxAppender {

	private final OutboxRepository outboxRepository;
	private final ApplicationEventPublisher eventPublisher;

	/** 적재 - 호출자의 도메인 트랜잭션에 합류한다. 적재 사실 이벤트는 커밋 뒤에 깨우기가 듣는다. */
	@Override
	@Transactional
	public void append(IngestionEventType type, String aggregateId) {
		outboxRepository.save(Outbox.pending(type, aggregateId));
		// 트랜잭션 안에서 발행해도 AFTER_COMMIT 리스너(OutboxDispatchWaker)는 커밋 성공 뒤에만 실행된다.
		eventPublisher.publishEvent(new OutboxAppended(type, aggregateId));
	}

	/** 미발행 행 선점 - 반드시 발송 트랜잭션 안에서 호출한다. limit 이 0 이면 상한 없이 전부. */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Outbox> claimPending(int limit, OutboxClaimStrategy strategy, OutboxReadSource readSource) {
		return outboxRepository.claimPending(limit, strategy, readSource);
	}

	/** 발행 완료 표시. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void markSent(Outbox outbox, LocalDateTime now) {
		outbox.markSent(now);
		outboxRepository.save(outbox);
	}

	/** 배정 스트림이 없는 사실의 즉시 종결 - 발행을 거치지 않고 이 자리에서 SENT로 닫는다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void markSentWithoutStream(Outbox outbox, LocalDateTime now) {
		outbox.markSentWithoutStream(now);
		outboxRepository.save(outbox);
	}

	/** 발행 실패 기록 - 상한 판단은 엔티티가 한다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void markPublishFailed(Outbox outbox, int maxAttempts) {
		outbox.markPublishFailed(maxAttempts);
		outboxRepository.save(outbox);
	}

	/** 발행 실패 목록 - 관리자 화면이 읽는다. */
	@Transactional(readOnly = true)
	public List<Outbox> findFailed(int limit) {
		return outboxRepository.findFailed(limit);
	}

	/** 미발행 행 수 - 적체 관측이 읽는다. */
	@Transactional(readOnly = true)
	public long countPending() {
		return outboxRepository.countPending();
	}

	/** 관리자 재시도 - FAILED 행을 PENDING으로 되돌린다. */
	@Transactional
	public Outbox retry(long outboxId) {
		Outbox outbox = outboxRepository.findById(outboxId)
				.orElseThrow(() -> new CoreException(IngestionErrorCode.OUTBOX_NOT_FOUND));
		if (!outbox.isFailed()) {
			throw new CoreException(IngestionErrorCode.OUTBOX_NOT_RETRYABLE);
		}
		outbox.retry();
		return outboxRepository.save(outbox);
	}

	/** 정리 - 적재 시각이 보존 기간을 지난 발행 완료 행을 소량 삭제한다. */
	@Transactional
	public int cleanupSent(LocalDateTime threshold, int limit) {
		List<Outbox> targets = outboxRepository.findSentBefore(threshold, limit);
		if (targets.isEmpty()) {
			return 0;
		}
		outboxRepository.deleteAll(targets);
		return targets.size();
	}
}
