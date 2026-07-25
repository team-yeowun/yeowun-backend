package modi.backend.ingestion.domain;

/**
 * 분야별 구분 — <b>수집 요청의 필수 조건</b>이다.
 * <p>
 * {@link ExhibitionRealm}(세부 분류)보다 한 단계 위의 묶음이라 의미가 겹친다. 전시({@code D000})를 수집할 때
 * {@link #PERFORMANCE_EXHIBITION}을 함께 보내도 결과가 달라지지 않는 것이 실측으로 확인됐다 —
 * 그래도 원천이 요구하는 필수 파라미터이므로 항상 명시해 보낸다.
 * <p>
 * 원천 코드({@code serviceTp})를 상수마다 들고 있어 어댑터에 번역 스위치문이 생기지 않는다.
 */
public enum CatalogServiceType {

	/** 공연/전시. */
	PERFORMANCE_EXHIBITION("A"),
	/** 행사/축제. */
	EVENT_FESTIVAL("B"),
	/** 교육/체험. */
	EDUCATION_EXPERIENCE("C");

	private final String code;

	CatalogServiceType(String code) {
		this.code = code;
	}

	/** 원천 요청 파라미터 {@code serviceTp}에 실을 값. */
	public String code() {
		return code;
	}
}
