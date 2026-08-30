package modi.backend.ingestionv2.stage.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.support.error.CoreException;

/**
 * 스테이징 애그리거트 루트.
 *
 * <ul>
 *   <li>행 = 전시 1건의 코어 반영 사실, 상관 키는 문화포털 원천 키(UNIQUE)</li>
 *   <li>재시도 상한 판정의 근거인 시도 횟수를 도메인이 직접 보유(상한값 자체는 설정 소유)</li>
 *   <li>상태 전이는 전부 이 클래스의 메서드 안에서만 발생</li>
 *   <li>종결(STAGED)은 되돌릴 수 없으며 성공 전이와 실패 전이 양쪽이 같은 가드를 가짐</li>
 * </ul>
 */
@Entity
@Table(name = "ingestion_staging",
		uniqueConstraints = @UniqueConstraint(name = "uk_ingestion_staging_vendor_key", columnNames = "vendor_key"),
		indexes = @Index(name = "idx_ingestion_staging_status_updated_at", columnList = "status, updated_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Staging {

	/** 실패 요약 보관 길이. 원문 스택은 로그에 있고 여기에는 관리자 목록에 뜰 만큼만 남긴다. */
	private static final int ERROR_SUMMARY_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "vendor_key", nullable = false, length = 100)
	private String vendorKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private StagingStatus status;

	@Column(name = "staged_exhibition_id")
	private Long stagedExhibitionId;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "last_error", length = ERROR_SUMMARY_LENGTH)
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "staged_at")
	private LocalDateTime stagedAt;

	private Staging(String vendorKey, LocalDateTime now) {
		this.vendorKey = vendorKey;
		this.status = StagingStatus.PENDING;
		this.attempts = 0;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** 반영 대기 상태로 생성한다. */
	public static Staging pending(String vendorKey, LocalDateTime now) {
		return new Staging(vendorKey, now);
	}

	/** 코어 등록이 끝난 사실을 확정한다. 이미 종결된 행에 다시 부르는 것은 불변식 위반이다. */
	public void markStaged(long exhibitionId, LocalDateTime now) {
		if (this.status == StagingStatus.STAGED) {
			throw new CoreException(StageErrorCode.INVALID_STAGE_TRANSITION);
		}
		this.status = StagingStatus.STAGED;
		this.stagedExhibitionId = exhibitionId;
		this.lastError = null;
		this.stagedAt = now;
		this.updatedAt = now;
	}

	/**
	 * 실패를 한 번 기록하고 다음에 할 일을 알린다. 상한을 넘으면 자동 회생을 중단한다.
	 *
	 * <ul>
	 *   <li>이미 종결된 행이면 아무것도 바꾸지 않고 ALREADY_SETTLED 를 돌려준다</li>
	 *   <li>실패 기록은 반영 트랜잭션의 잠금 밖(REQUIRES_NEW)에서 돌므로 이 가드가 유일한 방어선이다</li>
	 *   <li>상한값은 설정이 진실이므로 인자로 받는다</li>
	 * </ul>
	 */
	public StageFailureOutcome recordFailure(String summary, LocalDateTime now, int maxAttempts) {
		if (this.status == StagingStatus.STAGED) {
			return StageFailureOutcome.ALREADY_SETTLED;
		}
		this.attempts = this.attempts + 1;
		this.lastError = summarize(summary);
		this.status = this.attempts >= maxAttempts ? StagingStatus.FAILED : StagingStatus.PENDING;
		this.updatedAt = now;
		return this.status == StagingStatus.FAILED ? StageFailureOutcome.EXHAUSTED : StageFailureOutcome.RETRYABLE;
	}

	/** 관리자 수동 재시도. 시도 횟수를 되돌려 자동 회생 경로에 다시 올린다. */
	public void reopen(LocalDateTime now) {
		if (this.status != StagingStatus.FAILED) {
			throw new CoreException(StageErrorCode.INVALID_STAGE_TRANSITION);
		}
		this.status = StagingStatus.PENDING;
		this.attempts = 0;
		this.updatedAt = now;
	}

	public boolean isStaged() {
		return this.status == StagingStatus.STAGED;
	}

	public boolean isAbandoned() {
		return this.status == StagingStatus.FAILED;
	}

	private static String summarize(String summary) {
		if (summary == null) {
			return null;
		}
		return summary.length() <= ERROR_SUMMARY_LENGTH ? summary : summary.substring(0, ERROR_SUMMARY_LENGTH);
	}
}
