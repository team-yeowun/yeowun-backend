package modi.backend.ingestion.infra.mock;

import modi.backend.ingestion.infra.google.GoogleMapsClient;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import modi.backend.domain.exhibition.hours.PlaceHoursData;
import modi.backend.ingestion.domain.data.PlaceHoursFetch;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;

/**
 * 외부 호출 없는 mock 영업시간 조회기(기본 provider). 로컬·CI·develop에서 유료 구글 호출을 <b>0</b>으로 막고,
 * 데모/개발 화면에도 영업시간이 뜨도록 고정 샘플을 반환한다.
 * <p>
 * 샘플(전형적 미술관): 화~일 10:00~18:00, 월 휴무 → 표시 규칙 적용 시 {@code 매일 10:00 ~ 18:00} + {@code 월 휴무}.
 * 실호출({@link GoogleMapsClient})과 함께 빈으로 공존하며, {@code app.exhibition.place-hours.provider=google}이고
 * 키가 있을 때만 실호출기가 @Primary로 선택된다.
 */
@Component
public class MockPlaceHoursProvider implements PlaceHoursProvider {

	private static final LocalTime OPEN = LocalTime.of(10, 0);
	private static final LocalTime CLOSE = LocalTime.of(18, 0);

	@Override
	public Optional<PlaceHoursFetch> fetch(String placeName, String placeAddr) {
		WeeklyOpeningHours.Builder builder = WeeklyOpeningHours.builder();
		// 월요일(MONDAY)은 넣지 않아 휴무. 화~일 동일 시간.
		for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
				DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY }) {
			builder.add(day, OPEN, CLOSE);
		}
		// mock은 벤더 원문이 없다(vendor=null) — 스냅샷은 비고 정준층에 provider=MOCK으로만 남는다.
		return Optional.of(new PlaceHoursFetch(new PlaceHoursData(builder.build()), null));
	}

	/**
	 * mock이 만든 값은 정준층에 {@code provider=MOCK}으로 남아 실호출 결과와 구분된다 —
	 * 로컬·develop 기본이 mock이라 이 구분이 없으면 "진짜 영업시간"과 "가짜"가 DB에서 섞인다.
	 */
	@Override
	public PlaceHoursVendor vendor() {
		return PlaceHoursVendor.MOCK;
	}
}
