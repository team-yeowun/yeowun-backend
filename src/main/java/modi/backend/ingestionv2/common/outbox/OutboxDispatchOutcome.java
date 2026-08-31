package modi.backend.ingestionv2.common.outbox;

/**
 * 발송 한 배치의 결과 두 수.
 *
 * <ul>
 *   <li>claimed = 조회로 집어 온 행 수, published = 이 인스턴스가 실제로 맡아 처리한 행 수</li>
 *   <li>둘을 가르는 이유 - 마커 판정에서 진 행은 집었지만 처리하지 않는다. 이 차이가 곧 읽기 낭비의 크기이고,
 *       소진 루프가 같은 행을 붙잡고 헛도는 것을 막는 종료 조건이기도 하다</li>
 * </ul>
 */
public record OutboxDispatchOutcome(int claimed, int published) {

	public static OutboxDispatchOutcome of(int claimed, int published) {
		return new OutboxDispatchOutcome(claimed, published);
	}

	/** 이 배치로 소진 루프를 이어 갈 수 있는가 - 집을 것도 없거나 하나도 맡지 못했으면 이 틱은 끝이다. */
	public boolean drainable() {
		return claimed > 0 && published > 0;
	}
}
