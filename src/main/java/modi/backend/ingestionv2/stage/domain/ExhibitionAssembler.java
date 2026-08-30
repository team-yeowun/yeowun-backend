package modi.backend.ingestionv2.stage.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.support.error.CoreException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원장을 코어 등록 입력으로 옮기는 순수 변환.
 *
 * <ul>
 *   <li>기록은 원문이고 해석은 어셈블이라는 원칙에 따라 타입 복원이 여기서 발생</li>
 *   <li>완비 판단은 점검의 몫이라 여기서 다시 검증하지 않음</li>
 *   <li>점검이 통과시킨 값의 복원 실패는 프로그래밍 오류라 예외로 노출</li>
 *   <li>상세 원장의 absent 와 좌표 결측은 정상 관측이라 null 로 조립</li>
 * </ul>
 */
@Component("ingestionV2ExhibitionAssembler")
@RequiredArgsConstructor
public class ExhibitionAssembler {

	/** 점검의 InspectionRule 이 쓰는 포맷과 같아야 한다. 어긋나면 통과한 값이 여기서 터진다. */
	private static final DateTimeFormatter VENDOR_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	/** 코어 정준층의 표시값 길이 상한. 넘치면 잘라 담고 원문은 원장에 남긴다. */
	private static final int HOURS_MAX_LENGTH = 500;

	/** 읽기 전용이라 상태가 없고 스레드 안전하다. 도메인이 스프링 배선에 기대지 않도록 자체 보유. */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final StageLedgerReader ledgerReader;

	/** 원장 3종을 등록 입력으로 조립한다. 점검을 통과한 전시에서만 호출된다. */
	public ExhibitionRegistration assemble(String vendorKey) {
		StageLedger.Listing listing = ledgerReader.readListing(vendorKey)
				.orElseThrow(() -> new CoreException(StageErrorCode.LEDGER_INCOMPLETE));
		StageLedger.Genre genre = ledgerReader.readGenre(vendorKey)
				.orElseThrow(() -> new CoreException(StageErrorCode.LEDGER_INCOMPLETE));
		Optional<StageLedger.Detail> detail = ledgerReader.readDetail(vendorKey)
				.filter(found -> !found.absent());

		return new ExhibitionRegistration(
				listing.vendorKey(),
				requireText(listing.title()),
				listing.place(),
				ExhibitionRegion.fromAreaText(listing.area()),
				listing.sigungu(),
				toCoordinate(listing.gpsX()),
				toCoordinate(listing.gpsY()),
				requireDate(listing.startDate()),
				requireDate(listing.endDate()),
				ExhibitionCategory.fromRealmName(listing.realmName()),
				listing.thumbnail(),
				detailUrlOf(listing, detail),
				listing.serviceName(),
				detail.map(StageLedger.Detail::price).orElse(null),
				detail.map(StageLedger.Detail::contents).orElse(null),
				detail.map(StageLedger.Detail::imgUrl).orElse(null),
				detail.map(StageLedger.Detail::place).orElse(null),
				detail.map(StageLedger.Detail::phone).orElse(null),
				detail.map(StageLedger.Detail::url).orElse(null),
				requireText(genre.keyword()),
				toProvider(genre.provider()),
				genre.model());
	}

	/**
	 * 구글 원장을 코어 개장 시간 입력으로 조립한다. 원장이 없으면 비어 있음을 돌려주고 반영을 생략한다.
	 *
	 * <ul>
	 *   <li>조회했으나 장소를 못 찾은 경우와 장소는 찾았으나 시간이 없는 경우를 코어 상태 어휘로 구분</li>
	 *   <li>구조 보존 JSON 이라 평문 추출이 여기서 발생</li>
	 * </ul>
	 */
	public Optional<HoursInput> assembleHours(String vendorKey) {
		return ledgerReader.readPlace(vendorKey).map(place -> {
			if (place.absent()) {
				return new HoursInput(null, PlaceHoursStatus.NOT_FOUND);
			}
			String formatted = flattenWeekdayDescriptions(place.regularOpeningHours());
			return new HoursInput(formatted,
					formatted == null ? PlaceHoursStatus.NO_HOURS : PlaceHoursStatus.SUCCEEDED);
		});
	}

	/** 개장 시간 반영 입력. 코어 계약 어휘인 원시값과 코어 enum 만 담는다. */
	public record HoursInput(String formatted, PlaceHoursStatus status) {
	}

	private static String flattenWeekdayDescriptions(String openingHoursJson) {
		if (openingHoursJson == null || openingHoursJson.isBlank()) {
			return null;
		}
		try {
			JsonNode descriptions = JSON.readTree(openingHoursJson).path("weekdayDescriptions");
			if (!descriptions.isArray() || descriptions.isEmpty()) {
				return null;
			}
			StringBuilder joined = new StringBuilder();
			for (JsonNode line : descriptions) {
				if (!joined.isEmpty()) {
					joined.append('\n');
				}
				joined.append(line.asString());
			}
			String formatted = joined.toString();
			return formatted.length() <= HOURS_MAX_LENGTH ? formatted : formatted.substring(0, HOURS_MAX_LENGTH);
		} catch (RuntimeException malformed) {
			return null;
		}
	}

	private static String detailUrlOf(StageLedger.Listing listing, Optional<StageLedger.Detail> detail) {
		if (listing.detailUrl() != null && !listing.detailUrl().isBlank()) {
			return listing.detailUrl();
		}
		return detail.map(StageLedger.Detail::url).orElse(null);
	}

	/** 점검이 비어있지 않음을 확인한 값. 그럼에도 비어 있다면 판단이 어긋난 것이다. */
	private static String requireText(String value) {
		if (value == null || value.isBlank()) {
			throw new CoreException(StageErrorCode.LEDGER_VALUE_MALFORMED);
		}
		return value.trim();
	}

	/** 점검이 파싱 가능함을 확인한 값. 복원되지 않으면 조용히 넘기지 않고 드러낸다. */
	private static LocalDate requireDate(String value) {
		if (value == null || value.isBlank()) {
			throw new CoreException(StageErrorCode.LEDGER_VALUE_MALFORMED);
		}
		try {
			return LocalDate.parse(value.trim(), VENDOR_DATE);
		} catch (DateTimeParseException malformed) {
			throw new CoreException(StageErrorCode.LEDGER_VALUE_MALFORMED);
		}
	}

	/** 점검 항목이 아니라 부재는 정상이다. 값이 있는데 복원되지 않는 경우만 드러낸다. */
	private static Double toCoordinate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Double.valueOf(value.trim());
		} catch (NumberFormatException malformed) {
			throw new CoreException(StageErrorCode.LEDGER_VALUE_MALFORMED);
		}
	}

	/** 장르 공급자는 코어 enum 이다. 원장의 문자열이 코어 어휘에 없으면 드러낸다. */
	private static GenreProvider toProvider(String value) {
		if (value == null || value.isBlank()) {
			throw new CoreException(StageErrorCode.LEDGER_VALUE_MALFORMED);
		}
		try {
			return GenreProvider.valueOf(value.trim());
		} catch (IllegalArgumentException unknown) {
			throw new CoreException(StageErrorCode.LEDGER_VALUE_MALFORMED);
		}
	}
}
