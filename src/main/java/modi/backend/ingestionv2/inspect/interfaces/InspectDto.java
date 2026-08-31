package modi.backend.ingestionv2.inspect.interfaces;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import modi.backend.ingestionv2.inspect.domain.InspectResult;

/**
 * 관리자 점검 API 응답.
 *
 * <ul>
 *   <li>열거형은 이름 문자열로 변환 (화면이 값을 그대로 표시)</li>
 *   <li>집합은 이름 순으로 정렬한 목록으로 변환 (응답 순서를 고정해 화면이 흔들리지 않게 함)</li>
 *   <li>원장 단면이 없으면 null (결손 전시의 진단 화면이 열려야 함)</li>
 * </ul>
 */
public final class InspectDto {

	private InspectDto() {
	}

	public record RejectedItemResponse(String vendorKey, List<String> rejectReasons, List<String> notes,
			LocalDateTime inspectedAt) {

		public static RejectedItemResponse from(InspectResult.Summary summary) {
			return new RejectedItemResponse(summary.vendorKey(), names(summary.rejectReasons()),
					names(summary.notes()), summary.inspectedAt());
		}
	}

	public record RejectedPageResponse(List<RejectedItemResponse> items, int page, int size, long totalCount,
			int totalPages) {

		public static RejectedPageResponse from(InspectResult.RejectedPage result) {
			return new RejectedPageResponse(
					result.items().stream().map(RejectedItemResponse::from).toList(),
					result.page(), result.size(), result.totalCount(), result.totalPages());
		}
	}

	public record DetailResponse(String vendorKey, String status, List<String> rejectReasons, List<String> notes,
			LocalDateTime inspectedAt, LedgerResponse ledger) {

		public static DetailResponse from(InspectResult.Detail result) {
			InspectResult.Summary summary = result.inspection();
			return new DetailResponse(summary.vendorKey(), summary.status().name(),
					names(summary.rejectReasons()), names(summary.notes()), summary.inspectedAt(),
					result.ledger() == null ? null : LedgerResponse.from(result.ledger()));
		}
	}

	public record LedgerResponse(String title, String startDate, String endDate, String area, String gpsX,
			String gpsY, String genreKeyword, boolean placeAbsent, boolean openingHoursPresent) {

		public static LedgerResponse from(InspectResult.LedgerView ledger) {
			return new LedgerResponse(ledger.title(), ledger.startDate(), ledger.endDate(), ledger.area(),
					ledger.gpsX(), ledger.gpsY(), ledger.genreKeyword(), ledger.placeAbsent(),
					ledger.openingHoursPresent());
		}
	}

	/** 집합의 순회 순서는 보장되지 않는다. 새로고침마다 사유 순서가 바뀌지 않도록 여기서 고정한다. */
	private static List<String> names(Set<? extends Enum<?>> values) {
		return values.stream().map(Enum::name).sorted(Comparator.naturalOrder()).toList();
	}
}
