package modi.backend.ingestionv2.enrich.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.enrich.domain.detail.EnrichmentDetail;
import modi.backend.ingestionv2.enrich.domain.genre.EnrichmentGenre;
import modi.backend.ingestionv2.enrich.domain.hours.EnrichmentHours;
import modi.backend.support.error.CoreException;

/**
 * 보강 애그리거트 루트.
 *
 * <ul>
 *   <li>하위 셋을 소유. 하위 상태 전이는 전부 이 클래스를 거침</li>
 *   <li>열림 판정(onDetailDone)과 완료 판정(completeIfAllDone)을 모두 소유</li>
 *   <li>재시도 상한 판정도 소유. 관리자 화면이 조회하는 실패 횟수가 도메인에 있어야 하기 때문</li>
 *   <li>상한 값은 소유하지 않음. 설정이 진실이므로 판정 메서드가 인자로 받음</li>
 *   <li>실패 기록 셋 모두 종결 상태 가드를 지남. 끝난 스텝과 완료된 루트를 뒤집지 않음</li>
 * </ul>
 */
@Entity
@Table(name = "ingestion_enrichment",
		uniqueConstraints = @UniqueConstraint(name = "uk_ingestion_enrichment_vendor_key", columnNames = "vendor_key"),
		indexes = @Index(name = "idx_ingestion_enrichment_status", columnList = "status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrichment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "vendor_key", nullable = false, unique = true, length = 100)
	private String vendorKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private EnrichmentStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	/** 조회 메서드를 만들지 않는다. 하위 참조가 밖으로 나가면 서비스가 전이를 직접 부를 수 있게 된다. */
	@Getter(AccessLevel.NONE)
	@OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "detail_id", nullable = false, unique = true)
	private EnrichmentDetail detail;

	@Getter(AccessLevel.NONE)
	@OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "genre_id", nullable = false, unique = true)
	private EnrichmentGenre genre;

	@Getter(AccessLevel.NONE)
	@OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "hours_id", nullable = false, unique = true)
	private EnrichmentHours hours;

	private Enrichment(String vendorKey) {
		this.vendorKey = vendorKey;
		this.status = EnrichmentStatus.ENRICHING;
		this.createdAt = IngestionClock.now();
		this.detail = EnrichmentDetail.opened(vendorKey);
		this.genre = EnrichmentGenre.pending(vendorKey);
		this.hours = EnrichmentHours.pending(vendorKey);
	}

	/** 수집 완료 사실을 받아 보강을 시작한다. 상세만 열린 상태로 만들어진다. */
	public static Enrichment start(String vendorKey) {
		return new Enrichment(vendorKey);
	}

	/**
	 * 열림 판정. 상세 완료를 반영하고 이번 호출이 새로 연 스텝을 돌려준다.
	 *
	 * <ul>
	 *   <li>이미 DONE이면 빈 목록. 같은 이벤트가 두 번 도착해도 스텝이 두 번 열리지 않음</li>
	 *   <li>돌려준 목록이 곧 다음에 발행할 이벤트의 목록</li>
	 * </ul>
	 */
	public List<EnrichStep> onDetailDone() {
		if (!detail.markDone(EnrichmentDetail.VENDOR)) {
			return List.of();
		}
		List<EnrichStep> opened = new ArrayList<>();
		if (genre.open()) {
			opened.add(EnrichStep.GENRE);
		}
		if (hours.open()) {
			opened.add(EnrichStep.HOURS);
		}
		return opened;
	}

	/** 장르 완료 반영. 성공 벤더와 폴백 사실을 함께 남긴다. */
	public void onGenreDone(String vendor, boolean fallbackUsed) {
		genre.markDone(vendor, fallbackUsed);
	}

	/** 개장 시간 완료 반영. */
	public void onHoursDone() {
		hours.markDone(EnrichmentHours.VENDOR);
	}

	/**
	 * 완료 판정. 하위 셋이 전부 DONE이면 COMPLETED로 전이하고 true를 돌려준다.
	 *
	 * <ul>
	 *   <li>ENRICHING이 아니면 항상 false. 완료를 보는 트랜잭션이 정확히 하나가 됨</li>
	 *   <li>true를 받은 쪽만 완료 이벤트를 적재</li>
	 * </ul>
	 */
	public boolean completeIfAllDone() {
		if (status != EnrichmentStatus.ENRICHING) {
			return false;
		}
		if (!(detail.isDone() && genre.isDone() && hours.isDone())) {
			return false;
		}
		this.status = EnrichmentStatus.COMPLETED;
		this.completedAt = IngestionClock.now();
		return true;
	}

	/**
	 * 시도 실패 기록과 상한 판정.
	 *
	 * <ul>
	 *   <li>스텝이 DONE이거나 루트가 COMPLETED면 ALREADY_DONE. 늦게 도착한 실패가 종결을 뒤집지 못함</li>
	 *   <li>상한 미만이면 RETRY. 스텝은 READY에 머물러 재시도 대상으로 남음</li>
	 *   <li>상한 도달이면 그 스텝과 루트를 FAILED로 전이하고 EXHAUSTED</li>
	 *   <li>상한 값은 설정이 소유. 호출자가 넘겨준다</li>
	 * </ul>
	 */
	public FailureOutcome recordFailure(EnrichStep step, String vendor, String error, int maxAttempts) {
		EnrichmentStepRecord record = stepOf(step);
		if (isSettled(record)) {
			return FailureOutcome.ALREADY_DONE;
		}
		record.recordAttemptFailure(vendor, error);
		if (!record.isExhausted(maxAttempts)) {
			return FailureOutcome.RETRY;
		}
		record.markFailed(vendor, error);
		this.status = EnrichmentStatus.FAILED;
		return FailureOutcome.EXHAUSTED;
	}

	/** 장르 실패 기록. 폴백 사실을 함께 남기는 점만 다르다. */
	public FailureOutcome recordGenreFailure(String vendor, String error, boolean fallbackUsed, int maxAttempts) {
		if (isSettled(genre)) {
			return FailureOutcome.ALREADY_DONE;
		}
		genre.recordAttemptFailure(vendor, error, fallbackUsed);
		if (!genre.isExhausted(maxAttempts)) {
			return FailureOutcome.RETRY;
		}
		genre.markFailed(vendor, error);
		this.status = EnrichmentStatus.FAILED;
		return FailureOutcome.EXHAUSTED;
	}

	/**
	 * 다시 시도해도 결과가 달라지지 않는 실패. 시도 횟수와 무관하게 즉시 확정한다.
	 *
	 * <ul>
	 *   <li>이번 호출이 실제로 확정했으면 true. 이미 종결된 스텝이면 false이고 아무 것도 바뀌지 않음</li>
	 * </ul>
	 */
	public boolean failWithoutRetry(EnrichStep step, String reason) {
		EnrichmentStepRecord record = stepOf(step);
		if (isSettled(record)) {
			return false;
		}
		record.markFailed(null, reason);
		this.status = EnrichmentStatus.FAILED;
		return true;
	}

	/**
	 * 관리자 수동 재시도. 실패한 스텝만 다시 열고 그 목록을 돌려준다.
	 *
	 * <ul>
	 *   <li>FAILED가 아닌 보강은 거절. 진행 중인 건을 건드리면 시도 횟수가 초기화됨</li>
	 *   <li>돌려준 목록이 곧 다시 적재할 실행 이벤트의 목록</li>
	 *   <li>루트를 ENRICHING으로 되돌려 완료 판정이 다시 성립할 수 있게 함</li>
	 * </ul>
	 */
	public List<EnrichStep> reopenFailedSteps() {
		if (status != EnrichmentStatus.FAILED) {
			throw new CoreException(EnrichErrorCode.ENRICHMENT_NOT_FAILED);
		}
		List<EnrichStep> reopened = new ArrayList<>();
		if (detail.reopenFailed()) {
			reopened.add(EnrichStep.DETAIL);
		}
		if (genre.reopenFailed()) {
			reopened.add(EnrichStep.GENRE);
		}
		if (hours.reopenFailed()) {
			reopened.add(EnrichStep.HOURS);
		}
		this.status = EnrichmentStatus.ENRICHING;
		return reopened;
	}

	/** 종결 판정. 끝난 스텝과 완료된 루트는 실패 기록의 대상이 아니다. */
	private boolean isSettled(EnrichmentStepRecord record) {
		return record.isDone() || status == EnrichmentStatus.COMPLETED;
	}

	public boolean isStepDone(EnrichStep step) {
		return stepOf(step).isDone();
	}

	/** 관리자 화면이 묻는 값. 하위 엔티티를 넘기지 않고 값만 돌려준다. */
	public StepStatus statusOf(EnrichStep step) {
		return stepOf(step).getStatus();
	}

	public int attemptsOf(EnrichStep step) {
		return stepOf(step).getAttempts();
	}

	public String lastErrorOf(EnrichStep step) {
		return stepOf(step).getLastError();
	}

	public String lastAttemptVendorOf(EnrichStep step) {
		return stepOf(step).getLastAttemptVendor();
	}

	/** 장르만 갖는 값. */
	public boolean isGenreFallbackUsed() {
		return genre.isFallbackUsed();
	}

	private EnrichmentStepRecord stepOf(EnrichStep step) {
		return switch (step) {
			case DETAIL -> detail;
			case GENRE -> genre;
			case HOURS -> hours;
		};
	}
}
