package modi.backend.ingestionv2.common.inbox;

/** 논리 subscriber가 한 이벤트를 처리한 상태. */
public enum InboxStatus {
	PROCESSING,
	SUCCEEDED,
	DEAD_LETTERED,
	FAILED;

	public boolean isTerminal() {
		return this == SUCCEEDED || this == DEAD_LETTERED;
	}
}
