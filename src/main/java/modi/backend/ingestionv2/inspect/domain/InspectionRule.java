package modi.backend.ingestionv2.inspect.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Set;

import modi.backend.domain.exhibition.catalog.ExhibitionRegion;

/**
 * 원장 단면 -&gt; 판정. 순수 계산이며 상태를 갖지 않는다.
 *
 * <ul>
 *   <li>어셈블이 쓰는 것과 같은 벤더 날짜 포맷으로 파싱해 "어셈블에서 null이 될 값"을 미리 잡음</li>
 *   <li>지역 판정은 코어 함수를 그대로 호출 (매핑 규칙을 두 곳에 두지 않기 위함)</li>
 *   <li>개장 시간은 조회 확정 여부만 봄 (값의 부재는 코어가 정상으로 정의한 케이스)</li>
 * </ul>
 */
public final class InspectionRule {

	/** 스테이징 ExhibitionAssembler 의 VENDOR_DATE 와 같은 포맷. 이 둘이 어긋나면 통과한 값이 어셈블에서 터진다. */
	private static final DateTimeFormatter VENDOR_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private InspectionRule() {
	}

	public static InspectionVerdict evaluate(InspectionLedger ledger) {
		Set<RejectReason> rejectReasons = EnumSet.noneOf(RejectReason.class);
		Set<InspectionNote> notes = EnumSet.noneOf(InspectionNote.class);

		if (isBlank(ledger.title())) {
			rejectReasons.add(RejectReason.TITLE_BLANK);
		}
		if (isBlank(ledger.genreKeyword())) {
			rejectReasons.add(RejectReason.GENRE_BLANK);
		}

		LocalDate startDate = parseDate(ledger.startDate());
		LocalDate endDate = parseDate(ledger.endDate());
		if (startDate == null) {
			rejectReasons.add(RejectReason.START_DATE_UNPARSABLE);
		}
		if (endDate == null) {
			rejectReasons.add(RejectReason.END_DATE_UNPARSABLE);
		}
		if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
			rejectReasons.add(RejectReason.PERIOD_REVERSED);
		}

		if (ExhibitionRegion.fromAreaText(ledger.area()) == ExhibitionRegion.ETC) {
			notes.add(InspectionNote.REGION_UNMAPPED);
		}
		if (parseCoordinate(ledger.gpsX()) == null || parseCoordinate(ledger.gpsY()) == null) {
			notes.add(InspectionNote.COORDINATE_UNPARSABLE);
		}
		if (ledger.placeAbsent()) {
			notes.add(InspectionNote.HOURS_PLACE_NOT_FOUND);
		} else if (isBlank(ledger.openingHours())) {
			notes.add(InspectionNote.HOURS_EMPTY);
		}

		return new InspectionVerdict(rejectReasons, notes);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static LocalDate parseDate(String value) {
		if (isBlank(value)) {
			return null;
		}
		try {
			return LocalDate.parse(value.trim(), VENDOR_DATE);
		} catch (DateTimeParseException malformed) {
			return null;
		}
	}

	private static Double parseCoordinate(String value) {
		if (isBlank(value)) {
			return null;
		}
		try {
			return Double.valueOf(value.trim());
		} catch (NumberFormatException malformed) {
			return null;
		}
	}
}
