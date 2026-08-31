package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;

/**
 * 회차 시작 마크.
 *
 * <ul>
 *   <li>행 1개 = 이 회차를 어느 인스턴스가 선점했다는 사실</li>
 *   <li>batch_date UNIQUE = 분산 락 인프라 없이 회차당 1회 실행을 만드는 물리 제약</li>
 *   <li>상태 컬럼 없음 (선점 여부는 행의 존재로 충분)</li>
 * </ul>
 */
@Entity
@Table(
		name = "ingestion_collect_batch_mark",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_ingestion_collect_batch_mark_batch_date",
				columnNames = "batch_date"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectBatchMark {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "batch_date", nullable = false)
	private LocalDate batchDate;

	@Column(name = "claimed_at", nullable = false, updatable = false)
	private LocalDateTime claimedAt;

	private CollectBatchMark(LocalDate batchDate, LocalDateTime claimedAt) {
		this.batchDate = batchDate;
		this.claimedAt = claimedAt;
	}

	/** 이 회차의 선점을 시도할 마크를 만든다. 실제 선점 성패는 유일 제약이 판정한다. */
	public static CollectBatchMark claim(LocalDate batchDate) {
		return new CollectBatchMark(batchDate, IngestionClock.now());
	}
}
