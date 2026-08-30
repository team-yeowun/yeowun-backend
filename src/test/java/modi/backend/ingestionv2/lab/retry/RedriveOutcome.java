package modi.backend.ingestionv2.lab.retry;

/**
 * 재처리 요청 한 건의 결과 - 지표 8종의 원자 단위.
 *
 * <ul>
 *   <li>{@code outcome} 은 성공이면 {@code SUCCESS}, 실패면 오류 코드 이름 또는 예외 클래스 이름</li>
 *   <li>{@code requestNanos} 는 호출 전체, {@code txNanos} 는 트랜잭션 경계 안 - 1-tx 변형만 채운다</li>
 *   <li>성공과 실패의 지연을 <b>같은 배열에 섞지 않는다</b>(계획서 지표 3·4) - 집계는 이 플래그로 가른다</li>
 * </ul>
 */
record RedriveOutcome(boolean success, String outcome, long requestNanos, long txNanos) {

	static final String SUCCESS = "SUCCESS";

	static RedriveOutcome succeeded(long requestNanos, long txNanos) {
		return new RedriveOutcome(true, SUCCESS, requestNanos, txNanos);
	}

	static RedriveOutcome rejected(String outcome, long requestNanos, long txNanos) {
		return new RedriveOutcome(false, outcome, requestNanos, txNanos);
	}

	double requestMillis() {
		return requestNanos / 1_000_000d;
	}

	double txMillis() {
		return txNanos / 1_000_000d;
	}
}
