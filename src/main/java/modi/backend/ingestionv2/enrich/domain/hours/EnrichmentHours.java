package modi.backend.ingestionv2.enrich.domain.hours;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStepRecord;
import modi.backend.ingestionv2.enrich.domain.StepStatus;

/**
 * 개장 시간 보강 진행 기록.
 *
 * <ul>
 *   <li>상세가 끝나야 열리므로 생성 시점은 PENDING</li>
 *   <li>장르와 서로 독립이라 둘의 실행 순서를 가정하지 않음</li>
 * </ul>
 */
@Entity
@Table(name = "ingestion_enrichment_hours")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnrichmentHours extends EnrichmentStepRecord {

	public static final String VENDOR = "GOOGLE";

	private EnrichmentHours(String vendorKey) {
		super(vendorKey, StepStatus.PENDING);
	}

	public static EnrichmentHours pending(String vendorKey) {
		return new EnrichmentHours(vendorKey);
	}
}
