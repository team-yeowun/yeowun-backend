package modi.backend.domain.bookmark;

/**
 * 관심 전시 목록 정렬 축. 전시 탐색({@code ExhibitionSort})과 <b>일부러 분리</b>한다.
 *
 * <p>같은 코드 문자열을 쓰지만 의미도 구현도 다르다.
 * <ul>
 *   <li>{@link #LATEST} — 전시 시작일이 아니라 <b>내가 관심 등록한 시각</b> 순이다. 정렬 키가
 *       북마크 테이블에 있어 거기서 페이지를 자른다.</li>
 *   <li>{@link #ENDING} — 정렬 키가 전시 컬럼이라 전시 조회에 정렬·경계를 위임한다.</li>
 * </ul>
 * 여기에 인기순·거리순은 없다. 하나의 enum으로 합치면 "이 목록에서 쓸 수 없는 값"을 매번 걸러야 한다.
 */
public enum BookmarkSort {

	/** 등록 최신순(기본) — 북마크 {@code created_at} 내림차순, 동률은 북마크 id 내림차순. */
	LATEST("latest"),
	/** 종료 임박순 — 전시 종료일 오름차순(미상은 뒤로). */
	ENDING("ending");

	private final String code;

	BookmarkSort(String code) {
		this.code = code;
	}

	/** 정렬 코드 → enum. 미정의 코드·null은 {@link #LATEST}로 수렴한다(목록 순서일 뿐이라 400을 던지지 않는다). */
	public static BookmarkSort from(String code) {
		if (code == null || code.isBlank()) {
			return LATEST;
		}
		String normalized = code.trim().toLowerCase();
		for (BookmarkSort sort : values()) {
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

	/** 정렬 키가 전시 컬럼이라 전시 조회 쪽에 정렬을 위임해야 하는가. */
	public boolean sortedByExhibitionColumn() {
		return this == ENDING;
	}
}
