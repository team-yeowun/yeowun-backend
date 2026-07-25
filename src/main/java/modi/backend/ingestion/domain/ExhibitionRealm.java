package modi.backend.ingestion.domain;

/**
 * 수집 대상 분야 — "우리가 어떤 문화 콘텐츠를 가져오는가"라는 사업 결정의 어휘(수집 도메인 소유).
 * <p>
 * 원천 코드({@code realmCode})를 상수가 들고 있어 어댑터에 번역 스위치문이 생기지 않는다.
 * <p>
 * 값이 하나인 이유는 지금 전시만 수집하기 때문이다. 원천의 전체 코드표는 아래와 같으므로, 수집 분야를 넓힐 때
 * 해당 상수를 더하면 된다 —
 * A000 연극 · B000 음악/콘서트 · B002 국악 · B003 뮤지컬/오페라 · C000 무용/발레 · <b>D000 전시</b> ·
 * E000 아동/가족 · F000 행사/축제 · G000 교육/체험 · H000 도서 · I000 체육 · L000 기타.
 */
public enum ExhibitionRealm {

	/** 전시. */
	EXHIBITION("D000");

	private final String code;

	ExhibitionRealm(String code) {
		this.code = code;
	}

	/** 원천 요청 파라미터 {@code realmCode}에 실을 값. */
	public String code() {
		return code;
	}
}
