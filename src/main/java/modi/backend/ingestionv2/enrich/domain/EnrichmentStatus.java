package modi.backend.ingestionv2.enrich.domain;

/**
 * 보강 한 건의 결말.
 *
 * <ul>
 *   <li>ENRICHING: 하위 스텝이 하나라도 남아 있는 상태</li>
 *   <li>COMPLETED: 하위 셋이 전부 DONE이 되어 점검으로 넘길 수 있는 상태</li>
 *   <li>FAILED: 하위 하나가 재시도 상한을 소진해 전진할 수 없는 상태</li>
 * </ul>
 */
public enum EnrichmentStatus {

	ENRICHING,
	COMPLETED,
	FAILED
}
