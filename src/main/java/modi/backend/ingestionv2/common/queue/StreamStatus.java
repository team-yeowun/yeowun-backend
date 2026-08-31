package modi.backend.ingestionv2.common.queue;

/**
 * 스트림 하나의 상태 스냅숏.
 *
 * <ul>
 *   <li>length는 트리밍 지표, pendingCount는 소비 지표 - 둘이 답하는 질문이 다름</li>
 *   <li>lag은 보조 지표 - 트리밍으로 계산이 불가능해지면 Redis가 비워 보낸다</li>
 *   <li>groupExists가 거짓이면 부트스트랩이 실행되지 않았다는 뜻</li>
 * </ul>
 */
public record StreamStatus(String streamKey, boolean groupExists, long length, long consumerCount,
		long pendingCount, Long lag) {

	public static StreamStatus of(String streamKey, long length, long consumerCount, long pendingCount, Long lag) {
		return new StreamStatus(streamKey, true, length, consumerCount, pendingCount, lag);
	}

	public static StreamStatus groupMissing(String streamKey, long length) {
		return new StreamStatus(streamKey, false, length, 0L, 0L, null);
	}
}
