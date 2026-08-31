package modi.backend.ingestionv2.inspect.domain;

import java.util.Set;

/**
 * 검증 규칙의 결과.
 *
 * <ul>
 *   <li>반려 사유와 기록 사항을 분리 보관 (통과 여부는 반려 사유만으로 결정)</li>
 *   <li>애그리거트가 이 값을 받아 상태를 스스로 정함</li>
 * </ul>
 */
public record InspectionVerdict(Set<RejectReason> rejectReasons, Set<InspectionNote> notes) {

	public boolean rejected() {
		return !rejectReasons.isEmpty();
	}
}
