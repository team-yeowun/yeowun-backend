package modi.backend.ingestionv2.common.inbox;

/** Inbox 선점 결과. token은 ACQUIRED일 때만 존재한다. */
public record InboxClaim(State state, String eventId, String token) {

	public enum State {
		ACQUIRED,
		IN_PROGRESS,
		TERMINAL
	}

	public static InboxClaim acquired(String eventId, String token) {
		return new InboxClaim(State.ACQUIRED, eventId, token);
	}

	public static InboxClaim inProgress(String eventId) {
		return new InboxClaim(State.IN_PROGRESS, eventId, null);
	}

	public static InboxClaim terminal(String eventId) {
		return new InboxClaim(State.TERMINAL, eventId, null);
	}

	public boolean acquired() {
		return state == State.ACQUIRED;
	}
}
