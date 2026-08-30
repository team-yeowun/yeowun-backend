package modi.backend.ingestionv2.inspect.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 점검 유스케이스 출력.
 *
 * <ul>
 *   <li>엔티티를 밖으로 내보내지 않음 (인터페이스 계층은 이 값까지만 앎)</li>
 *   <li>원장 단면은 존재 여부와 원문 문자열만 (개장 시간 JSON은 화면에 펼치지 않음)</li>
 *   <li>ledger가 null일 수 있음 (원장이 결손된 전시를 진단하는 화면이므로 예외 대신 빈 값)</li>
 * </ul>
 */
public final class InspectResult {

	private InspectResult() {
	}

	public record Summary(String vendorKey, InspectionStatus status, Set<RejectReason> rejectReasons,
			Set<InspectionNote> notes, LocalDateTime inspectedAt) {

		public static Summary from(Inspection inspection) {
			return new Summary(inspection.getVendorKey(), inspection.getStatus(), inspection.rejectReasons(),
					inspection.notes(), inspection.getInspectedAt());
		}
	}

	public record RejectedPage(List<Summary> items, int page, int size, long totalCount) {

		public int totalPages() {
			return size == 0 ? 0 : (int) Math.ceil((double) totalCount / size);
		}
	}

	public record Detail(Summary inspection, LedgerView ledger) {
	}

	public record LedgerView(String title, String startDate, String endDate, String area, String gpsX, String gpsY,
			String genreKeyword, boolean placeAbsent, boolean openingHoursPresent) {

		public static LedgerView from(InspectionLedger ledger) {
			return new LedgerView(ledger.title(), ledger.startDate(), ledger.endDate(), ledger.area(),
					ledger.gpsX(), ledger.gpsY(), ledger.genreKeyword(), ledger.placeAbsent(),
					ledger.openingHours() != null && !ledger.openingHours().isBlank());
		}
	}
}
