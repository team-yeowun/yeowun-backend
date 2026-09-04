package modi.backend.ingestionv2.common.inbox;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** Inbox 저장 포트의 MySQL 어댑터. */
@Repository
@RequiredArgsConstructor
public class InboxRepositoryImpl implements InboxRepository {

	private final InboxJpaRepository inboxJpaRepository;

	@Override
	public InboxClaim claim(String subscriberKey, String eventId, String token,
			LocalDateTime startedAt, LocalDateTime leaseUntil) {
		int inserted = inboxJpaRepository.insertIfAbsent(subscriberKey, eventId, token, startedAt, leaseUntil);
		if (inserted == 1) {
			return InboxClaim.acquired(subscriberKey, eventId, token);
		}
		int reclaimed = inboxJpaRepository.reclaimIfAvailable(
				subscriberKey, eventId, token, startedAt, leaseUntil);
		if (reclaimed == 1) {
			return InboxClaim.acquired(subscriberKey, eventId, token);
		}
		InboxStatus status = inboxJpaRepository.findStatus(subscriberKey, eventId)
				.map(InboxStatus::valueOf)
				.orElseThrow(() -> new IllegalStateException("Inbox 선점 결과 행을 찾을 수 없습니다. eventId=" + eventId));
		return status.isTerminal()
				? InboxClaim.terminal(subscriberKey, eventId)
				: InboxClaim.inProgress(subscriberKey, eventId);
	}

	@Override
	public boolean finish(InboxClaim claim, InboxStatus terminalStatus, LocalDateTime completedAt) {
		return inboxJpaRepository.finish(claim.subscriberKey(), claim.eventId(), claim.token(),
				terminalStatus.name(), completedAt) == 1;
	}

	@Override
	public boolean fail(InboxClaim claim, String lastError) {
		return inboxJpaRepository.fail(
				claim.subscriberKey(), claim.eventId(), claim.token(), lastError) == 1;
	}

	@Override
	public int cleanupTerminal(LocalDateTime threshold, int limit) {
		List<InboxMessage> targets = inboxJpaRepository
				.findByStatusInAndCompletedAtBeforeOrderByCompletedAtAscIdAsc(
						List.of(InboxStatus.SUCCEEDED, InboxStatus.DEAD_LETTERED),
						threshold, PageRequest.of(0, limit));
		if (targets.isEmpty()) {
			return 0;
		}
		inboxJpaRepository.deleteAll(targets);
		return targets.size();
	}
}
