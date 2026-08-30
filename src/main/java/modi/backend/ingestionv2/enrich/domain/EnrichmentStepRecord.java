package modi.backend.ingestionv2.enrich.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;

/**
 * 하위 스텝 진행 기록의 공통부.
 *
 * <ul>
 *   <li>마지막 상태 1행만 보관. 시도마다 한 줄씩 쌓는 이력 테이블을 만들지 않음</li>
 *   <li>상태 전이 메서드는 루트만 호출. 루트가 하위 참조를 밖으로 내주지 않아 서비스는 손에 넣을 수 없음</li>
 *   <li>재시도 상한 판정의 근거인 attempts 를 이 계층이 소유. 배달 계층의 전달 횟수를 쓰지 않음</li>
 *   <li>상한 값 자체는 소유하지 않음. 판정 메서드가 인자로 받아 설정 한 곳만 진실이 되게 함</li>
 * </ul>
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EnrichmentStepRecord {

	/** last_error 컬럼 상한. */
	private static final int ERROR_MAX = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "vendor_key", nullable = false, unique = true, length = 100)
	private String vendorKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private StepStatus status;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "last_attempt_vendor", length = 50)
	private String lastAttemptVendor;

	@Column(name = "last_error", length = ERROR_MAX)
	private String lastError;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	protected EnrichmentStepRecord(String vendorKey, StepStatus status) {
		this.vendorKey = vendorKey;
		this.status = status;
		this.attempts = 0;
	}

	/** 선행 조건 충족으로 열기. 이번 호출이 실제로 열었으면 true. */
	public boolean open() {
		if (status != StepStatus.PENDING) {
			return false;
		}
		this.status = StepStatus.READY;
		return true;
	}

	/** 완료 전이. 이미 DONE이면 아무 일도 하지 않아 재전달에 안전. */
	public boolean markDone(String vendor) {
		if (status == StepStatus.DONE) {
			return false;
		}
		this.status = StepStatus.DONE;
		this.lastAttemptVendor = vendor;
		this.lastError = null;
		this.completedAt = IngestionClock.now();
		return true;
	}

	/** 시도 1회 실패 기록. 상태는 READY에 머물러 재시도 대상으로 남는다. */
	public void recordAttemptFailure(String vendor, String error) {
		this.attempts = this.attempts + 1;
		this.lastAttemptVendor = vendor;
		this.lastError = shorten(error);
	}

	/** 전진 불가 확정. */
	public void markFailed(String vendor, String error) {
		this.status = StepStatus.FAILED;
		this.lastAttemptVendor = vendor;
		this.lastError = shorten(error);
	}

	/**
	 * 관리자 수동 재시도. 실패로 확정된 스텝만 다시 열고 시도 횟수를 0으로 되돌린다.
	 *
	 * <ul>
	 *   <li>FAILED가 아니면 아무 일도 하지 않고 false. 끝난 스텝을 다시 열지 않음</li>
	 *   <li>마지막 오류는 지우고 마지막 시도 벤더는 남김. 재시도 전에 무엇이 막혔는지는 이력으로 필요</li>
	 * </ul>
	 */
	public boolean reopenFailed() {
		if (status != StepStatus.FAILED) {
			return false;
		}
		this.status = StepStatus.READY;
		this.attempts = 0;
		this.lastError = null;
		return true;
	}

	public boolean isDone() {
		return status == StepStatus.DONE;
	}

	/** 상한 판정. 상한 값은 설정이 갖고 있으므로 호출자가 넘겨준다. */
	public boolean isExhausted(int maxAttempts) {
		return attempts >= maxAttempts;
	}

	private static String shorten(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX);
	}
}
