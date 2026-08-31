package modi.backend.ingestionv2.common.event;

/**
 * 사실을 낸 애그리거트의 종류.
 *
 * <ul>
 *   <li>아웃박스 aggregate_type 컬럼의 어휘 - 대기열 라우팅과 파티션 키의 근거</li>
 *   <li>격벽 넷과 일대일 - 격벽이 늘면 여기도 는다</li>
 * </ul>
 */
public enum IngestionAggregateType {
	COLLECTION,
	ENRICHMENT,
	INSPECTION,
	STAGING
}
