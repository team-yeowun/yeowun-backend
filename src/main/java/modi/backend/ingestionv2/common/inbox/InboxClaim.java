package modi.backend.ingestionv2.common.inbox;

/** Inbox 선점 결과. token은 ACQUIRED일 때만 존재한다. */
public record InboxClaim(State state, String subscriberKey, String eventId, String token) {

	public enum State {
		ACQUIRED,
		IN_PROGRESS,
		TERMINAL
	}

	public static InboxClaim acquired(String subscriberKey, String eventId, String token) {
		return new InboxClaim(State.ACQUIRED, subscriberKey, eventId, token);
	}

	public static InboxClaim inProgress(String subscriberKey, String eventId) {
		return new InboxClaim(State.IN_PROGRESS, subscriberKey, eventId, null);
	}

	public static InboxClaim terminal(String subscriberKey, String eventId) {
		return new InboxClaim(State.TERMINAL, subscriberKey, eventId, null);
	}

	public boolean acquired() {
		return state == State.ACQUIRED;
	}
}
