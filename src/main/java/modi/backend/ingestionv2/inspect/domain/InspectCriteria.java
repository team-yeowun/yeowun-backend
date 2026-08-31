package modi.backend.ingestionv2.inspect.domain;

/**
 * 점검 유스케이스 입력.
 *
 * <ul>
 *   <li>조회 상한을 여기서 잠금 (요청 형식이 아니라 서비스 보호 규칙)</li>
 *   <li>오프셋 계산을 값 안에 둠 (컨트롤러와 서비스가 같은 계산을 하지 않게 함)</li>
 * </ul>
 */
public final class InspectCriteria {

	private InspectCriteria() {
	}

	public record RejectedSearch(RejectReason reason, int page, int size) {

		private static final int DEFAULT_SIZE = 20;
		private static final int MAX_SIZE = 100;

		public static RejectedSearch of(RejectReason reason, int page, int size) {
			int safePage = Math.max(page, 0);
			int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
			return new RejectedSearch(reason, safePage, safeSize);
		}

		public int offset() {
			return page * size;
		}
	}
}
