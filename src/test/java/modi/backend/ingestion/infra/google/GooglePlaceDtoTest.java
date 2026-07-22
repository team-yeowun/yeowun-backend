package modi.backend.ingestion.infra.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import modi.backend.ingestion.infra.google.GooglePlaceDto.GooglePlaceResponse;
import modi.backend.ingestion.infra.google.GooglePlaceDto.Place;
import modi.backend.ingestion.infra.google.GooglePlaceDto.Place.RegularOpeningHours;
import modi.backend.ingestion.infra.google.GooglePlaceDto.Place.RegularOpeningHours.Period;
import modi.backend.ingestion.infra.google.GooglePlaceDto.Place.RegularOpeningHours.TimePoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;
import modi.backend.ingestion.domain.data.PlaceHoursResult;

/**
 * 구글 응답 record가 <b>자기를 도메인 값으로 표현</b>하는 계약 검증(순수 단위 — HTTP·리포지토리 없음).
 * <p>
 * 이 파싱은 예전엔 {@code GooglePlaceClient} 안에 있어 전송·감사와 얽혀 단독 검증이 불가능했다.
 * record로 내려오면서 순수 함수가 됐고, 그래서 여기서 벤더 어휘(0=일요일 인덱스·결측·범위 밖)를 직접 찌른다.
 */
class GooglePlaceDtoTest {

	private static TimePoint at(int day, int hour, Integer minute) {
		return new TimePoint(day, hour, minute);
	}

	private static RegularOpeningHours hours(Period... periods) {
		return new RegularOpeningHours(List.of(periods), null);
	}

	@Test
	@DisplayName("구글 day 인덱스(0=일요일)를 java DayOfWeek로 옮긴다")
	void toWeekly_mapsGoogleDayIndexToDayOfWeek() {
		RegularOpeningHours source = hours(
				new Period(at(0, 10, 0), at(0, 18, 0)),
				new Period(at(6, 11, 30), at(6, 19, 30)));

		WeeklyOpeningHours weekly = source.toWeekly();

		assertThat(weekly.byDay()).containsOnlyKeys(DayOfWeek.SUNDAY, DayOfWeek.SATURDAY);
		assertThat(weekly.byDay().get(DayOfWeek.SUNDAY))
				.containsExactly(new WeeklyOpeningHours.TimeRange(LocalTime.of(10, 0), LocalTime.of(18, 0)));
		assertThat(weekly.byDay().get(DayOfWeek.SATURDAY))
				.containsExactly(new WeeklyOpeningHours.TimeRange(LocalTime.of(11, 30), LocalTime.of(19, 30)));
	}

	@Test
	@DisplayName("minute 결측은 0분으로 채운다")
	void toWeekly_treatsMissingMinuteAsZero() {
		WeeklyOpeningHours weekly = hours(new Period(at(1, 9, null), at(1, 17, null))).toWeekly();

		assertThat(weekly.byDay().get(DayOfWeek.MONDAY))
				.containsExactly(new WeeklyOpeningHours.TimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0)));
	}

	@Test
	@DisplayName("close가 없는 구간(24시간 영업 등)은 건너뛴다 — 아는 것만 남긴다")
	void toWeekly_skipsPeriodWithoutClose() {
		WeeklyOpeningHours weekly = hours(new Period(at(2, 0, 0), null)).toWeekly();

		assertThat(weekly.hasNoOpenDay()).isTrue();
	}

	@Test
	@DisplayName("day·hour가 구글 규격 범위를 벗어난 구간은 건너뛴다(배열 인덱스 사고 차단)")
	void toWeekly_skipsOutOfRangePoints() {
		WeeklyOpeningHours weekly = hours(
				new Period(at(7, 10, 0), at(7, 18, 0)),
				new Period(at(-1, 10, 0), at(-1, 18, 0)),
				new Period(at(3, 24, 0), at(3, 25, 0))).toWeekly();

		assertThat(weekly.hasNoOpenDay()).isTrue();
	}

	@Test
	@DisplayName("periods가 비었거나 없으면 빈 영업시간")
	void toWeekly_emptyWhenNoPeriods() {
		assertThat(hours().toWeekly().hasNoOpenDay()).isTrue();
		assertThat(new RegularOpeningHours(null, null).toWeekly().hasNoOpenDay()).isTrue();
	}

	@Test
	@DisplayName("장소는 찾았으나 영업시간이 없으면 빈 영업시간을 담아 결과를 만든다(재조회 대상에서 빠지도록)")
	void place_returnsEmptyHoursWhenNoPeriods() {
		Place place = new Place("places/abc",
				new Place.DisplayName("부산현대미술관", "ko"), "부산 사하구", null);

		assertThat(place.data().weeklyHours().hasNoOpenDay()).isTrue();
		assertThat(place.regularOpeningHoursJson()).isNull();
	}

	@Test
	@DisplayName("응답(Place)이 자기를 조회 결과로 표현 — 스냅샷 필드 + 구조 보존 JSON(ADR-13)")
	void place_exposesSnapshotFieldsAndStructuredHoursJson() {
		Place place = new Place("places/abc",
				new Place.DisplayName("부산현대미술관", "ko"), "부산 사하구 낙동남로 1191",
				hours(new Period(at(2, 10, 0), at(2, 18, 0))));

		assertThat(place.placeId()).isEqualTo("places/abc");
		assertThat(place.displayNameText()).isEqualTo("부산현대미술관");
		assertThat(place.formattedAddress()).isEqualTo("부산 사하구 낙동남로 1191");
		// 원문 구조가 보존돼야 한다 — 평탄화한 표시 문자열이 아니다.
		assertThat(place.regularOpeningHoursJson())
				.contains("\"periods\"").contains("\"day\":2").contains("\"hour\":10");
		assertThat(place.data().weeklyHours().byDay()).containsOnlyKeys(DayOfWeek.TUESDAY);
	}

	@Test
	@DisplayName("displayName이 없으면 displayNameText는 null(NPE 아님)")
	void place_toleratesMissingDisplayName() {
		Place place = new Place("places/abc", null, "부산 사하구", null);

		assertThat(place.displayNameText()).isNull();
	}

	@Test
	@DisplayName("첫 후보 장소만 쓴다 — 결과가 없으면 empty")
	void firstPlace_picksFirstOrEmpty() {
		Place first = new Place("places/1", null, "주소1", null);
		Place second = new Place("places/2", null, "주소2", null);

		assertThat(new GooglePlaceResponse(List.of(first, second)).firstPlace())
				.contains(first);
		assertThat(new GooglePlaceResponse(List.of()).firstPlace()).isEmpty();
		assertThat(new GooglePlaceResponse(null).firstPlace()).isEmpty();
	}

	@Test
	@DisplayName("toPlaceHours()가 응답→도메인값 진입점 — 1순위 후보를 조회 결과로 옮기고, 후보 없으면 empty")
	void toPlaceHours_isTheResponseToDomainEntry() {
		Place place = new Place("places/abc",
				new Place.DisplayName("부산현대미술관", "ko"), "부산 사하구",
				hours(new Period(at(2, 10, 0), at(2, 18, 0))));

		Optional<PlaceHoursResult> fetched = new GooglePlaceResponse(List.of(place)).toPlaceHours();

		assertThat(fetched).isPresent();
		assertThat(fetched.get().placeId()).isEqualTo("places/abc");
		assertThat(fetched.get().data().weeklyHours().byDay()).containsOnlyKeys(DayOfWeek.TUESDAY);
		// 후보 없음 = 미발견
		assertThat(new GooglePlaceResponse(List.of()).toPlaceHours()).isEmpty();
	}

	@Test
	@DisplayName("TimePoint.dayOfWeek()가 구글 day 인덱스를 요일로 옮긴다(0=일요일 … 6=토요일)")
	void timePoint_dayOfWeek_mapsGoogleIndex() {
		assertThat(new TimePoint(0, 10, 0).dayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
		assertThat(new TimePoint(1, 10, 0).dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(new TimePoint(6, 10, 0).dayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
	}
}
