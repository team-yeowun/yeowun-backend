package modi.backend.ingestionv2.common.inbox;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionProperties;

/** 짧은 트랜잭션으로 처리권을 선점하고 핸들러 실행 뒤 종결하는 Inbox 창구. */
@Service
@RequiredArgsConstructor
public class InboxService {

	private static final int MAX_ERROR_LENGTH = 1000;

	private final InboxRepository inboxRepository;
	private final IngestionProperties properties;

	@Transactional
	public InboxClaim claim(String subscriberKey, String eventId) {
		if (subscriberKey == null || subscriberKey.isBlank() || subscriberKey.length() > 100) {
			throw new IllegalArgumentException("Inbox subscriber key는 1~100자여야 합니다.");
		}
		try {
			UUID.fromString(eventId);
		} catch (IllegalArgumentException | NullPointerException malformed) {
			throw new IllegalArgumentException("Inbox eventId는 UUID여야 합니다. eventId=" + eventId, malformed);
		}
		LocalDateTime startedAt = IngestionClock.now();
		LocalDateTime leaseUntil = startedAt.plus(Duration.ofMillis(properties.inboxLeaseMs()));
		return inboxRepository.claim(subscriberKey, eventId, UUID.randomUUID().toString(), startedAt, leaseUntil);
	}

	@Transactional
	public boolean succeed(InboxClaim claim) {
		return finish(claim, InboxStatus.SUCCEEDED);
	}

	@Transactional
	public boolean deadLetter(InboxClaim claim) {
		return finish(claim, InboxStatus.DEAD_LETTERED);
	}

	@Transactional
	public boolean fail(InboxClaim claim, Throwable failure) {
		String summary = failure.getClass().getSimpleName() + ": " + failure.getMessage();
		if (summary.length() > MAX_ERROR_LENGTH) {
			summary = summary.substring(0, MAX_ERROR_LENGTH);
		}
		return inboxRepository.fail(claim, summary);
	}

	/** 종결된 멱등 키만 유한 보존 기간 뒤 소량 정리한다. PROCESSING과 FAILED는 복구 근거라 지우지 않는다. */
	@Transactional
	public int cleanupTerminal(LocalDateTime threshold, int limit) {
		return inboxRepository.cleanupTerminal(threshold, limit);
	}

	private boolean finish(InboxClaim claim, InboxStatus status) {
		if (!claim.acquired()) {
			return false;
		}
		if (!status.isTerminal()) {
			throw new IllegalArgumentException("Inbox 종결 상태가 아닙니다. status=" + status);
		}
		return inboxRepository.finish(claim, status, IngestionClock.now());
	}
}
