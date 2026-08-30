package modi.backend.ingestionv2.common;

/**
 * 배달 계층 관리자 유스케이스의 입력.
 *
 * <ul>
 *   <li>조회 상한은 입력에서 잠근다 - 격리가 수천 건 쌓인 상황에서도 응답 크기가 고정</li>
 *   <li>두 목록 조회가 같은 입력을 쓴다 - 상한 규칙이 한 곳에만 존재</li>
 * </ul>
 */
public final class IngestionDeliveryCriteria {

	private IngestionDeliveryCriteria() {
	}

	/** 목록 조회의 상한. 1 미만과 200 초과는 경계값으로 눕힌다. */
	public record Listing(int limit) {

		private static final int MIN_LIMIT = 1;
		private static final int MAX_LIMIT = 200;

		public Listing {
			limit = Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);
		}

		public static Listing of(int limit) {
			return new Listing(limit);
		}
	}

	/** 발행 실패 행 재시도 대상 지정. */
	public record OutboxRetry(long outboxId) {

		public static OutboxRetry of(long outboxId) {
			return new OutboxRetry(outboxId);
		}
	}

	/** 재주입 대상 지정. */
	public record Redrive(long deadLetterId) {

		public static Redrive of(long deadLetterId) {
			return new Redrive(deadLetterId);
		}
	}

	/** 무시 대상 지정. */
	public record Ignore(long deadLetterId) {

		public static Ignore of(long deadLetterId) {
			return new Ignore(deadLetterId);
		}
	}
}
