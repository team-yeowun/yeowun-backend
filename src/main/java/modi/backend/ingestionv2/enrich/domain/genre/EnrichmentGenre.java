package modi.backend.ingestionv2.enrich.domain.genre;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStepRecord;
import modi.backend.ingestionv2.enrich.domain.StepStatus;

/**
 * 장르 보강 진행 기록.
 *
 * <ul>
 *   <li>상세가 끝나야 열리므로 생성 시점은 PENDING</li>
 *   <li>폴백 사용 여부를 갖는 유일한 하위. 1차 공급자 실패율의 관측 지점</li>
 * </ul>
 */
@Entity
@Table(name = "ingestion_enrichment_genre")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnrichmentGenre extends EnrichmentStepRecord {

	/** 마지막 시도에서 폴백까지 갔는지 여부. */
	@Column(name = "fallback_used", nullable = false)
	private boolean fallbackUsed;

	private EnrichmentGenre(String vendorKey) {
		super(vendorKey, StepStatus.PENDING);
	}

	public static EnrichmentGenre pending(String vendorKey) {
		return new EnrichmentGenre(vendorKey);
	}

	/** 완료 전이와 폴백 사실 기록을 함께 한다. 성공 벤더가 실행 시점에 정해지므로 인자로 받는다. */
	public boolean markDone(String vendor, boolean fallback) {
		boolean transitioned = markDone(vendor);
		if (transitioned) {
			this.fallbackUsed = fallback;
		}
		return transitioned;
	}

	/** 실패 시에도 폴백까지 갔는지를 남긴다. 1차 공급자 한도 소진을 관측하기 위한 값이다. */
	public void recordAttemptFailure(String vendor, String error, boolean fallback) {
		recordAttemptFailure(vendor, error);
		this.fallbackUsed = fallback;
	}
}
