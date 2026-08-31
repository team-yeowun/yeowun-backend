package modi.backend.ingestionv2.lab.outbox;

/**
 * 계획서 "규모 사다리"의 네 점.
 *
 * <ul>
 *   <li>필수 3점(S0·S1·S2) + 선택 1점(S3) - 선택 점에 어떤 주장도 걸지 않는다</li>
 *   <li>S0 는 정리 스케줄러가 제 몫을 할 때의 정상 상태(보존 7일 x 일일 유입 3,500 = 24,500 을 25,000 으로 올린 값)</li>
 *   <li>전 점 공통으로 PENDING 정확히 1,000행, 나머지는 SENT</li>
 * </ul>
 */
enum OutboxLabScale {

	S0(25_000L, "정상 상태(보존 7일 x 일일 유입 3,500 = 24,500 의 올림값)"),
	S1(200_000L, "정리가 유입을 따라가지 못해 두 달쯤 누적된 상태"),
	S2(1_000_000L, "기준선 대용량"),
	S3(5_000_000L, "디스크 바운드 확인용(선택)");

	/** 전 규모 공통 - 미발행으로 남겨 두는 행 수. */
	static final int PENDING_ROWS = 1_000;

	/** 일일 유입 상한 - catalog.max-items 500 x IngestionEventType 7종. created_at 간격의 근거. */
	static final long ROWS_PER_DAY = 3_500L;

	private final long totalRows;
	private final String meaning;

	OutboxLabScale(long totalRows, String meaning) {
		this.totalRows = totalRows;
		this.meaning = meaning;
	}

	long totalRows() {
		return totalRows;
	}

	String meaning() {
		return meaning;
	}
}
