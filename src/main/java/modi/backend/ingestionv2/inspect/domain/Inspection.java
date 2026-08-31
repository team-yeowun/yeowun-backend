package modi.backend.ingestionv2.inspect.domain;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

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
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.support.error.CoreException;

/**
 * 점검 애그리거트 루트 (이력 테이블 없이 마지막 결론 1행만 보유).
 *
 * <ul>
 *   <li>전시 1건당 1행 (vendor_key UNIQUE = 중복 소비의 최종 방어선)</li>
 *   <li>반려 사유와 기록 사항은 enum 이름을 콤마로 이어 보관 (표시 전용, 조회 조건 아님)</li>
 *   <li>상태 결정은 전부 이 클래스 안 (사유가 비어 있으면 통과, 하나라도 있으면 반려)</li>
 * </ul>
 */
@Entity
@Table(name = "ingestion_inspection",
		uniqueConstraints = @UniqueConstraint(name = "uk_ingestion_inspection_vendor_key", columnNames = "vendor_key"),
		indexes = @Index(name = "idx_ingestion_inspection_status_inspected_at", columnList = "status, inspected_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inspection {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "vendor_key", nullable = false, length = 100)
	private String vendorKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private InspectionStatus status;

	/** 반려 사유 이름을 콤마로 이은 값. 통과면 null. */
	@Column(name = "reject_reasons", length = 300)
	private String rejectReasonCodes;

	/** 통과시키되 남겨두는 관찰 이름을 콤마로 이은 값. 없으면 null. */
	@Column(name = "notes", length = 300)
	private String noteCodes;

	@Column(name = "inspected_at", nullable = false)
	private LocalDateTime inspectedAt;

	private Inspection(String vendorKey, InspectionVerdict verdict) {
		this.vendorKey = vendorKey;
		apply(verdict);
	}

	/** 최초 점검 결과로 행을 만든다. */
	public static Inspection create(String vendorKey, InspectionVerdict verdict) {
		return new Inspection(vendorKey, verdict);
	}

	/** 관리자 재검사 (반려 상태에서만 허용 = 통과한 전시를 되돌리지 않는다). */
	public void reevaluate(InspectionVerdict verdict) {
		if (status != InspectionStatus.REJECTED) {
			throw new CoreException(InspectErrorCode.INSPECTION_NOT_REJECTED);
		}
		apply(verdict);
	}

	public boolean isPassed() {
		return status == InspectionStatus.PASSED;
	}

	public Set<RejectReason> rejectReasons() {
		return split(rejectReasonCodes, RejectReason.class);
	}

	public Set<InspectionNote> notes() {
		return split(noteCodes, InspectionNote.class);
	}

	private void apply(InspectionVerdict verdict) {
		this.status = verdict.rejected() ? InspectionStatus.REJECTED : InspectionStatus.PASSED;
		this.rejectReasonCodes = join(verdict.rejectReasons());
		this.noteCodes = join(verdict.notes());
		this.inspectedAt = IngestionClock.now();
	}

	private static String join(Set<? extends Enum<?>> values) {
		return values.isEmpty() ? null : values.stream().map(Enum::name).collect(Collectors.joining(","));
	}

	private static <E extends Enum<E>> Set<E> split(String stored, Class<E> type) {
		Set<E> parsed = EnumSet.noneOf(type);
		if (stored == null || stored.isBlank()) {
			return parsed;
		}
		for (String name : stored.split(",")) {
			parsed.add(Enum.valueOf(type, name));
		}
		return parsed;
	}
}
