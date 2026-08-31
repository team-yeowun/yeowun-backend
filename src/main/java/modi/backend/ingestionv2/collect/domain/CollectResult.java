package modi.backend.ingestionv2.collect.domain;

/** 수집 유스케이스 출력. */
public final class CollectResult {

	private CollectResult() {
	}

	/**
	 * 회차 결과 요약.
	 *
	 * <ul>
	 *   <li>claimed=false 는 실패가 아니라 다른 인스턴스가 이미 수행 중이라는 사실</li>
	 *   <li>skipped 는 실패가 아니라 이미 확정된 전시를 건너뛴 멱등 동작</li>
	 *   <li>failed 는 전시 1건짜리 트랜잭션이 롤백된 건수</li>
	 * </ul>
	 */
	public record Batch(boolean claimed, int collected, int skipped, int failed) {

		public static Batch notClaimed() {
			return new Batch(false, 0, 0, 0);
		}

		public static Batch of(int collected, int skipped, int failed) {
			return new Batch(true, collected, skipped, failed);
		}
	}
}
