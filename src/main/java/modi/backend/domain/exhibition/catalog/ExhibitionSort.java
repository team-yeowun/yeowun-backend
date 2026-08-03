package modi.backend.domain.exhibition.catalog;

/**
 * 전시 목록/탐색 정렬 축. 경계에서는 코드 문자열(latest 등)로 주고받고, 안에서는 이 enum으로 다룬다.
 *
 * <p><b>정렬 속성과 방향을 값으로 들고 있는 이유</b>: 정렬 규칙이 흩어지면 <b>키셋 경계와 ORDER BY가 어긋나</b>
 * 행이 누락되거나 중복된다. 어떤 컬럼을 어느 방향으로 세우는지를 한 곳에 모아 두면, 어댑터의 ORDER BY와
 * Specification의 커서 경계가 같은 출처를 본다.
 *
 * <p>최종 타이브레이커는 항상 {@code id}이며 정렬 컬럼과 같은 방향이다({@link #ascending()}).
 * {@link #DISTANCE}만은 DB가 정렬하지 않는다 — 좌표가 전시장에 있어 앱에서 거리로 세운다.
 */
public enum ExhibitionSort {

	/** 최신순(기본) — 시작일 내림차순. */
	LATEST("latest", "startDate", false),
	/** 종료 임박순 — 종료일 오름차순. */
	ENDING("ending", "endDate", true),
	/** 인기순 — 우리 앱 조회수 내림차순. */
	POPULAR("popular", "ourViewCount", false),
	/** 거리순 — DB 정렬 아님(좌표는 전시장에 있어 앱에서 계산·정렬한다). */
	DISTANCE("distance", null, false);

	private final String code;
	private final String property;
	private final boolean ascending;

	ExhibitionSort(String code, String property, boolean ascending) {
		this.code = code;
		this.property = property;
		this.ascending = ascending;
	}

	/**
	 * 정렬 코드 → enum. 미정의 코드·null은 {@link #LATEST}로 수렴한다.
	 *
	 * <p>섹션({@link ExhibitionSection})과 달리 400을 던지지 않는다 — 정렬은 <b>목록의 순서</b>일 뿐이라
	 * 오타 하나로 목록 자체를 못 보게 만들 이유가 없다(기존 동작 유지).
	 */
	public static ExhibitionSort from(String code) {
		if (code == null || code.isBlank()) {
			return LATEST;
		}
		String normalized = code.trim().toLowerCase();
		for (ExhibitionSort sort : values()) {
			if (sort.code.equals(normalized)) {
				return sort;
			}
		}
		return LATEST;
	}

	/** 경계·커서에 실리는 코드 문자열. */
	public String code() {
		return code;
	}

	/** 정렬 대상 엔티티 속성명. {@link #DISTANCE}는 null(DB 정렬 아님). */
	public String property() {
		return property;
	}

	/** 오름차순인가. 최종 타이브레이커 {@code id}도 같은 방향을 따른다. */
	public boolean ascending() {
		return ascending;
	}

	/** DB가 정렬하는가 — 거짓이면 앱에서 세운다(거리순). */
	public boolean sortedByDatabase() {
		return property != null;
	}

	/**
	 * 이 정렬에서 커서에 실을 값(정렬 컬럼의 마지막 값).
	 *
	 * <p>날짜 축은 <b>저장값(센티널 포함)</b>을 싣는다 — ORDER BY가 세우는 값과 커서 경계가 같은 값을 봐야
	 * 행이 누락·중복되지 않는다. 도메인 값(null 마스킹)을 실으면 경계가 다른 축을 보게 된다.
	 * 그래서 날짜 축은 정규화(V47) 이후 <b>null을 내지 않는다</b>(= nulls 블록이 사라졌다).
	 * {@link #DISTANCE}만 null이다(DB 정렬 아님).
	 */
	public String cursorKeyOf(Exhibition exhibition) {
		return switch (this) {
			case ENDING -> exhibition.endDateKey().toString();
			case POPULAR -> String.valueOf(exhibition.getOurViewCount());
			case LATEST -> exhibition.startDateKey().toString();
			case DISTANCE -> null; // 거리는 앱이 계산한 값이라 호출부가 직접 싣는다
		};
	}
}
