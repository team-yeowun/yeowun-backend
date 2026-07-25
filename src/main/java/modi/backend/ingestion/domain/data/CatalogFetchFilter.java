package modi.backend.ingestion.domain.data;

import java.time.LocalDate;

import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 목록 수집의 <b>선택 필터</b> — 값이 있는 것만 원천 요청에 실린다(없으면 그 파라미터 자체를 안 보낸다).
 * <p>
 * 분야({@code realm})·분야별 구분·정렬 기준은 <b>필수</b>라 여기 없다 — {@link CatalogFetchCriteria}에 있다.
 * <p>
 * 전부 nullable이다. "안 거는 필터"를 표현해야 하기 때문이며, 어댑터가
 * {@code queryParamIfPresent}로 없는 값을 걸러낸다.
 * <p>
 * <b>어휘는 도메인 것이다</b> — 원천의 파라미터명·코드값({@code sido="부산"}, {@code sortStdr=1})으로의 번역은
 * 그 벤더를 아는 어댑터가 한다. 여기 이름이 벤더를 따라가면 원천을 바꿀 때 호출부까지 흔들린다.
 *
 * @param region      시/도. 원천은 "서울"·"부산" 같은 텍스트를 받는데, 그 표기는 어댑터가 만든다.
 * @param from        기간 시작(해당일 이후). 원천 표기는 YYYYMMDD.
 * @param to          기간 종료(해당일 이전).
 * @param place       장소명 — <b>부분일치</b>다(실측: "국립" → 58건).
 * @param keyword     검색어 — 부분일치(실측: "미술" → 41건).
 * @param bounds      좌표 범위(사각형). 넷 중 하나만 줄 수 없어 한 덩어리로 묶었다.
 */
public record CatalogFetchFilter(
		ExhibitionRegion region,
		LocalDate from,
		LocalDate to,
		String place,
		String keyword,
		Bounds bounds) {

	private static final CatalogFetchFilter NONE =
			new CatalogFetchFilter(null, null, null, null, null, null);

	/** 필터 없음 — 원천에 페이징·분야만 보낸다. */
	public static CatalogFetchFilter none() {
		return NONE;
	}

	public CatalogFetchFilter {
		if (from != null && to != null && from.isAfter(to)) {
			throw new CoreException(ErrorType.INVALID_INPUT, "기간 시작이 종료보다 늦습니다: " + from + " ~ " + to);
		}
		place = blankToNull(place);
		keyword = blankToNull(keyword);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/**
	 * 좌표 사각형 — 남서(하한) · 북동(상한) 두 꼭짓점.
	 * <p>넷을 개별 파라미터로 두면 셋만 채운 반쪽 요청이 가능해지므로 한 덩어리로 강제한다.
	 */
	public record Bounds(double westLongitude, double southLatitude,
			double eastLongitude, double northLatitude) {

		public Bounds {
			if (westLongitude > eastLongitude) {
				throw new CoreException(ErrorType.INVALID_INPUT, "경도 하한이 상한보다 큽니다");
			}
			if (southLatitude > northLatitude) {
				throw new CoreException(ErrorType.INVALID_INPUT, "위도 하한이 상한보다 큽니다");
			}
		}
	}

}
