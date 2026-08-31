package modi.backend.ingestionv2.common.queue;

/**
 * 기다리는 시간에 무작위를 섞는 방식.
 *
 * <ul>
 *   <li>NONE: 같은 시각에 실패한 항목들이 같은 시각에 한꺼번에 다시 온다</li>
 *   <li>FULL: 0과 계산된 지연 사이에서 고른다 - 재전달 시점이 흩어져 한 순간의 봉우리가 낮아진다</li>
 * </ul>
 */
public enum ReclaimJitter {
	NONE,
	FULL
}
