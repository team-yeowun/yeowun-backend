package modi.backend.ingestion.domain;

/**
 * 목록 조회 정렬 기준 — <b>수집 요청의 필수 조건</b>이다(분야·페이지 크기와 같은 급).
 * <p>
 * 원천 코드({@code sortStdr})를 상수마다 들고 있어, 어댑터에 번역 스위치문이 흩어지지 않는다.
 * 값을 추가할 때 코드도 같은 줄에서 정해지므로 "enum만 늘리고 매핑을 빠뜨리는" 실수가 원천적으로 불가능하다.
 *
 * <p><b>실측으로 확인한 메뉴</b>(2026-07-21). 원천 문서는 1·2·3만 적고 있으나 실제로는 1~8이 모두 동작하며,
 * 오름차순/내림차순 쌍으로 짜여 있다. 문서의 라벨이 실제 동작과 어긋나는 것도 함께 적어둔다.
 * <table border="1">
 *   <caption>sortStdr 실측</caption>
 *   <tr><th>코드</th><th>실제 정렬</th><th>원천 문서</th></tr>
 *   <tr><td>1</td><td>시작일 오름차순</td><td>"등록일" — 라벨 불일치</td></tr>
 *   <tr><td>2</td><td>제목 오름차순</td><td>"공연명"</td></tr>
 *   <tr><td>3</td><td>장소 오름차순</td><td>"지역" — 실제로는 장소</td></tr>
 *   <tr><td>4~8</td><td>아래 상수 참고</td><td>문서에 없음(실측 확인)</td></tr>
 * </table>
 *
 * <p><b>문서에 없는 값을 쓰는 위험</b>: 계약이 없으므로 벤더가 언제든 바꿀 수 있다. 유효하지 않은 값
 * ({@code 0}·{@code 9} 이상·문자)은 기본 정렬로 조용히 폴백하지 <b>않고</b> {@code resultCode=00}인 채
 * <b>빈 목록</b>을 돌려준다 — 즉 코드가 무효가 되는 날 수집이 0건이 된다.
 *
 * <p>⚠️ <b>이 실패를 잡아주던 장치가 지금은 없다</b>(2026-07-21): 원래는 절단 판정({@code seen < totalCount})이
 * "원천은 280건이라는데 0건을 봤다"를 즉시 드러냈으나, 절단 감지가 폐기되면서(V42) 그 탐지가 사라졌다.
 * 운영 정렬이 문서에 없는 {@code REGISTRATION_DESC}(8)로 바뀌었으므로 <b>노출은 늘고 탐지는 없는 상태</b>다.
 * {@code totalCount > 0}인데 수집이 0건인 경우를 경고하는 가드를 두는 것이 이 위험의 값싼 대응이다(미적용).
 */
public enum CatalogSortOrder {

	/** 시작일 오름차순 — 오래된 전시가 먼저. 정렬을 지정하지 않았을 때와 결과가 같다(원천 문서 기재분). */
	START_DATE_ASC("1"),
	/** 제목 오름차순(원천 문서 기재분). */
	TITLE_ASC("2"),
	/** 장소 오름차순(원천 문서 기재분 — 문서 라벨은 "지역"이나 실제로는 장소 기준). */
	PLACE_ASC("3"),
	/** 시작일 내림차순 — <b>최신 전시가 먼저</b>. 문서에 없으나 실측 확인. */
	START_DATE_DESC("4"),
	/** 등록 순서(seq) 오름차순. 문서에 없으나 실측 확인. */
	REGISTRATION_ASC("5"),
	/** 제목 내림차순. 문서에 없으나 실측 확인. */
	TITLE_DESC("6"),
	/** 장소 내림차순. 문서에 없으나 실측 확인. */
	PLACE_DESC("7"),
	/** 등록 순서(seq) 내림차순 — 최근 등록분이 먼저. 문서에 없으나 실측 확인. */
	REGISTRATION_DESC("8");

	private final String code;

	CatalogSortOrder(String code) {
		this.code = code;
	}

	/** 원천 요청 파라미터 {@code sortStdr}에 실을 값. */
	public String code() {
		return code;
	}
}
