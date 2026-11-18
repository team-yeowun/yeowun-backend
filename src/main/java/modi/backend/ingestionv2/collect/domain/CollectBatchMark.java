package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수집 회차 실행 상태.
 *
 * <ul>
 *   <li>batch_date UNIQUE = 같은 회차의 동시 실행을 한 행으로 직렬화하는 물리 제약</li>
 *   <li>FAILED 또는 lease가 만료된 RUNNING만 재선점해 13시 복구 실행을 허용</li>
 *   <li>claim token = 이전 실행이 재선점된 실행의 완료·실패 상태를 덮어쓰지 못하게 하는 fence</li>
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

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private CollectBatchStatus status;

	@Column(name = "claim_token", length = 36)
	private String claimToken;

	@Column(name = "claimed_at", nullable = false)
	private LocalDateTime claimedAt;

	@Column(name = "lease_until")
	private LocalDateTime leaseUntil;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "last_error", length = 1000)
	private String lastError;

	/** FAILED 이거나 lease 가 지난 RUNNING 만 다른 실행이 다시 가져갈 수 있다. COMPLETED 와 유효한 RUNNING 은 닫혀 있다. */
	public boolean reclaimableAt(LocalDateTime now) {
		if (status == CollectBatchStatus.FAILED) {
			return true;
		}
		return status == CollectBatchStatus.RUNNING && leaseUntil != null && !leaseUntil.isAfter(now);
	}
}
