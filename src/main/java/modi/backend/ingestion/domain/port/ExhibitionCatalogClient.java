package modi.backend.ingestion.domain.port;


import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.data.CatalogPage;

import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;


/**
 * 외부 전시 API 수집 포트(ingestion 도메인 소유). 구현은 infra(DIP) — 외부 HTTP·응답 포맷을 도메인에서 감춘다.
 * 외부 장애/통신 실패는 {@link ExhibitionErrorCode#EXTERNAL_API_UNAVAILABLE}로 변환해 던진다.
 *
 * <p><b>코어는 이 포트를 쓰지 않는다</b>(2026-07-21): 서빙의 지연 상세 충전을 제거하면서 코어 소유 포트
 * {@code ExhibitionDetailClient}가 함께 사라졌다. 외부 전시 API 호출은 이제 <b>수집 슬라이스 안에서만</b> 일어난다.
 */
public interface ExhibitionCatalogClient {

	/**
	 * 목록 <b>한 페이지</b>를 가져온다 — 순회(어디까지 부를지)는 호출부의 몫이다.
	 *
	 * <p><b>왜 페이지 단위인가</b>: 수집 1회가 여러 콜이고, 그 <b>콜 하나하나가 감사·조기 종료의 단위</b>다.
	 * 어댑터가 순회를 끌어안으면 호출부는 "몇 번 불렀나"를 볼 수 없어 감사가 뭉개지고, "여기서 그만"도 정할 수 없다.
	 *
	 * @param criteria 무엇을 어떤 크기로 가져올지 — 어댑터가 아니라 호출자가 정한다
	 * @param pageNo   1부터. 상한({@link CatalogFetchCriteria#maxCalls()})은 호출부가 지킨다
	 */
	CatalogPage fetchPage(CatalogFetchCriteria criteria, int pageNo);

	/** 외부 호출이 가능한 상태인가(인증키 설정됨) — 아니면 호출부가 수집을 스킵한다(데모는 시드로 동작). */
	boolean isConfigured();

	/**
	 * 단건 상세(detail2)를 조회해 <b>도메인이 선언한 계약</b>({@link CultureDetailPayload})으로 돌려준다.
	 * 응답 원문 접근자와 도메인 값 변환({@code toDetail()})을 함께 제공하므로, 호출부가 스냅샷 적재와 도메인 반영을
	 * 같은 응답에서 할 수 있다 — 짝이 밀리는 오염이 원인부터 불가능하다.
	 *
	 * <p><b>항상 값을 준다</b>: 실측상 detail2는 유효한 seq에 18태그를 모두 채워 응답한다(2026-07-21, 30건 전수).
	 * 빈 응답({@code resultCode=00} + {@code <items/>})은 <b>없는 seq</b>에서만 나오므로, 목록에서 받은 seq를 부르는
	 * 이 경로에선 "목록 이후 원천에서 삭제됨"을 뜻한다 — 값 없음이 아니라 <b>예외</b>로 다룬다.
	 */
	CultureDetailPayload fetchDetail(String externalId);
}
