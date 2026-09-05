package modi.backend.ingestionv2.common.inbox;

import java.time.LocalDateTime;

/** Inbox 저장 포트. 생성·재선점·종결은 모두 현재 상태와 token을 조건으로 한 원자 SQL이다. */
public interface InboxRepository {

	InboxClaim claim(String subscriberKey, String eventId, String token,
			LocalDateTime startedAt, LocalDateTime leaseUntil);

	boolean finish(InboxClaim claim, InboxStatus terminalStatus, LocalDateTime completedAt);

	boolean fail(InboxClaim claim, String lastError);

	int cleanupTerminal(LocalDateTime threshold, int limit);
}
