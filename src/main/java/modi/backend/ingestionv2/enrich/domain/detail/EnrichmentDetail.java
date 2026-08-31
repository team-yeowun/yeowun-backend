package modi.backend.ingestionv2.enrich.domain.detail;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStepRecord;
import modi.backend.ingestionv2.enrich.domain.StepStatus;

/**
 * 상세 보강 진행 기록.
 *
 * <ul>
 *   <li>수집 직후 열려 있는 유일한 스텝. 생성 시점부터 READY</li>
 *   <li>벤더가 문화포털 하나로 고정이라 성공 벤더를 인자로 받지 않음</li>
 * </ul>
 */
@Entity
@Table(name = "ingestion_enrichment_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnrichmentDetail extends EnrichmentStepRecord {

	public static final String VENDOR = "CULTURE";

	private EnrichmentDetail(String vendorKey) {
		super(vendorKey, StepStatus.READY);
	}

	/** 수집 완료 시점에 이미 열린 상태로 생성한다. */
	public static EnrichmentDetail opened(String vendorKey) {
		return new EnrichmentDetail(vendorKey);
	}
}
