package modi.backend.ingestionv2.collect.domain;

/**
 * 수집 애그리거트의 상태.
 *
 * <ul>
 *   <li>COLLECTED 한 값 (확정 말고 다른 결말이 아직 없음)</li>
 *   <li>전이 없음 (값이 늘어날 때 여기에 더한다)</li>
 * </ul>
 */
public enum CollectionStatus {
	COLLECTED
}
