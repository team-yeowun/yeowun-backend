package modi.backend.domain.exhibition.catalog;

import java.util.Optional;

import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 전시 지역 enum. (03_전시.md RULE: 지역 — {@code ⚠가정} 실제 값 팀 확정 전 임시 셋)
 * 클라이언트 코드({@link #from})와 외부 API의 자유 텍스트 area({@link #fromAreaText})를 각각 받아들인다.
 */
public enum ExhibitionRegion {
	SEOUL("서울"), GYEONGGI("경기"), INCHEON("인천"), GANGWON("강원"), DAEJEON("대전"), SEJONG("세종"),
	CHUNGNAM("충남"), CHUNGBUK("충북"), GWANGJU("광주"), JEONNAM("전남"), JEONBUK("전북"), DAEGU("대구"),
	GYEONGBUK("경북"), BUSAN("부산"), ULSAN("울산"), GYEONGNAM("경남"), JEJU("제주"),
	/** 매핑되지 않은 지역 — 대표 표기가 없다(외부 조회 조건으로 쓸 수 없다). */
	ETC(null);

	private final String areaText;

	ExhibitionRegion(String areaText) {
		this.areaText = areaText;
	}

	/**
	 * {@link #fromAreaText}의 역방향 — 이 지역의 대표 표기("서울"·"부산"). {@link #ETC}는 {@code null}.
	 * <p>
	 * 완전한 역함수는 아니다. {@code fromAreaText}는 "부산광역시"까지 흡수하는 휴리스틱이라 여러 표기가 한 상수로
	 * 모이고, 여기서는 그중 대표 하나만 돌려준다. 외부 조회의 지역 조건으로 쓸 값이다.
	 */
	public String areaText() {
		return areaText;
	}

	/** 클라이언트가 보낸 지역 코드 → enum. 미정의 코드는 {@link ErrorType#INVALID_INPUT}. */
	public static ExhibitionRegion from(String code) {
		try {
			return valueOf(code.trim().toUpperCase());
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new CoreException(ErrorType.INVALID_INPUT, "정의되지 않은 지역 코드: " + code);
		}
	}

	/**
	 * 외부 전시 API의 area 자유 텍스트(예: "서울", "경기") → enum. 매핑 실패/공백은 {@link #ETC}.
	 * (지역 매핑 규칙은 실제 데이터 확정 전 임시 — 04_전시_구현.md 오픈 질문 참고)
	 */
	public static ExhibitionRegion fromAreaText(String area) {
		String text = Optional.ofNullable(area).map(String::trim).orElse("");
		if (text.startsWith("서울")) {
			return SEOUL;
		}
		if (text.startsWith("경기")) {
			return GYEONGGI;
		}
		if (text.startsWith("인천")) {
			return INCHEON;
		}
		if (text.startsWith("강원")) {
			return GANGWON;
		}
		if (text.startsWith("대전")) {
			return DAEJEON;
		}
		if (text.startsWith("광주")) {
			return GWANGJU;
		}
		if (text.startsWith("대구")) {
			return DAEGU;
		}
		if (text.startsWith("경북") || text.startsWith("경상북")) {
			return GYEONGBUK;
		}
		if (text.startsWith("부산")) {
			return BUSAN;
		}
		if (text.startsWith("울산")) {
			return ULSAN;
		}
		if (text.startsWith("경남") || text.startsWith("경상남")) {
			return GYEONGNAM;
		}
		if (text.startsWith("세종")) {
			return SEJONG;
		}
		if (text.startsWith("전남") || text.startsWith("전라남")) {
			return JEONNAM;
		}
		if (text.startsWith("전북") || text.startsWith("전라북")) {
			return JEONBUK;
		}
		if (text.startsWith("제주")) {
			return JEJU;
		}
		if (text.startsWith("충남") || text.startsWith("충청남")) {
			return CHUNGNAM;
		}
		if (text.startsWith("충북") || text.startsWith("충청북")) {
			return CHUNGBUK;
		}
		return ETC;
	}
}
