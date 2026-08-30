package modi.backend.ingestionv2.common.event;

/**
 * 파이프라인이 주고받는 사실의 어휘.
 *
 * <ul>
 *   <li>커맨드가 아니라 사실 - 이름이 "무엇을 하라"가 아니라 "무엇이 일어났다"</li>
 *   <li>공용 계층 소유 - 발행자 패키지에 두면 소비자가 발행자를 import 하게 됨</li>
 *   <li>사실마다 낸 애그리거트가 정해져 있음 - 아웃박스 aggregate_type은 여기서 파생</li>
 *   <li>STAGED는 소비자 없는 종결 사실 - 아웃박스에만 남고 스트림으로 나가지 않음</li>
 * </ul>
 */
public enum IngestionEventType {

	COLLECTED(IngestionAggregateType.COLLECTION),
	DETAIL_READY(IngestionAggregateType.ENRICHMENT),
	GENRE_READY(IngestionAggregateType.ENRICHMENT),
	HOURS_READY(IngestionAggregateType.ENRICHMENT),
	ENRICHED(IngestionAggregateType.ENRICHMENT),
	INSPECTED(IngestionAggregateType.INSPECTION),
	STAGED(IngestionAggregateType.STAGING);

	private final IngestionAggregateType aggregateType;

	IngestionEventType(IngestionAggregateType aggregateType) {
		this.aggregateType = aggregateType;
	}

	public IngestionAggregateType aggregateType() {
		return aggregateType;
	}
}
