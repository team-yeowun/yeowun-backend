package modi.backend.ingestion.domain.data;

import modi.backend.domain.exhibition.catalog.CatalogDetailData;

/**
 * 상세(detail2) 응답 한 건이 <b>수집 도메인에 보여야 하는 모양</b> — 도메인이 선언하고 infra 응답이 구현한다(DIP 역전).
 *
 * <p><b>왜 이 인터페이스가 있나</b>: 벤더 스냅샷({@code CultureDetailSnapshot})은 응답 원문을 그대로 적재해야 하는데,
 * 그렇다고 도메인이 Jackson 바인딩 타입({@code CultureDetail2Response})을 알 수는 없다. 그래서 <b>도메인이 필요한
 * 접근자만 선언</b>하고 infra의 응답 record가 이걸 구현한다 — 의존이 안쪽으로만 흐르면서 원문은 복사 없이 흐른다.
 *
 * <p>필드를 <b>복사한 DTO를 두지 않는 이유</b>가 여기 있다: 복사본은 원문과 같은 값을 두 벌 들고 다니게 되고,
 * 원천이 필드를 추가할 때 양쪽을 고쳐야 한다. 응답 객체가 직접 이 계약을 만족하면 그 중복이 없다.
 *
 * <p>접근자 이름이 벤더 표기({@code contents1})인 것은 의도다 — 이 계약이 서술하는 대상이 <b>벤더 응답</b>이기 때문이다.
 */
public interface CultureDetailPayload {

	String seq();

	String title();

	String startDate();

	String endDate();

	String place();

	String realmName();

	String area();

	String sigungu();

	String gpsX();

	String gpsY();

	String price();

	/** 워드프레스 블록/HTML 원문 — 평문 추출 <b>이전</b> 값이라 재추출 원료가 된다. */
	String contents1();

	String url();

	String phone();

	String imgUrl();

	String placeUrl();

	String placeAddr();

	String placeSeq();

	/**
	 * 이 응답을 도메인 상세 값으로 정제한다. <b>구현은 infra</b>가 한다 — 정제 규칙(이스케이프 원복·평문 추출)이
	 * 벤더 표기에 묶여 있고 Spring 디코더에 의존하기 때문이다.
	 */
	CatalogDetailData toDetail();
}
