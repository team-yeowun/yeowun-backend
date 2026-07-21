package modi.backend.ingestion.domain.port;

import java.util.Optional;

import modi.backend.ingestion.domain.data.CatalogFetchCriteria;
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.ingestion.domain.data.DetailFetch;

import modi.backend.domain.exhibition.catalog.ExhibitionDetailClient;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;


/**
 * 외부 전시 API 수집 포트(도메인 소유). 구현은 infra(DIP) — 외부 HTTP·응답 포맷을 도메인에서 감춘다.
 * 외부 장애/통신 실패는 {@link ExhibitionErrorCode#EXTERNAL_API_UNAVAILABLE}로 변환해 던진다.
 */
public interface ExhibitionCatalogClient extends ExhibitionDetailClient {

	/**
	 * 주어진 조건으로 적재 가능한 전시 수집 데이터를 가져온다 — 상한까지 나눠 부르는 것은 어댑터의 몫이다.
	 * 인증키 미설정 시 외부 호출 없이 {@link CatalogListData#none()}을 반환한다(데모는 시드 데이터로 동작).
	 *
	 * @param criteria 무엇을 얼마나 가져올지(분야·페이지 크기·수집 상한) — 어댑터가 아니라 호출자가 정한다
	 * @return 수집 데이터 + 원천이 말한 총 건수·절단 여부(호출부가 sync_run에 남긴다)
	 */
	CatalogListData fetchAll(CatalogFetchCriteria criteria);

	/**
	 * 단건 상세(detail2)를 벤더 원문과 함께 조회한다(수집 경로 — 도메인 반영과 스냅샷 적재가 같은 응답에서 나온다).
	 * 인증키 미설정/결과 없음은 빈 Optional.
	 */
	Optional<DetailFetch> fetchDetailSnapshot(String externalId);

	/** 코어 지연 상세 조회({@code ExhibitionDetailClient}) — 수집 조회에서 도메인 값만 취한다. */
	@Override
	default Optional<modi.backend.domain.exhibition.catalog.CatalogDetailData> fetchDetail(String externalId) {
		return fetchDetailSnapshot(externalId).map(DetailFetch::data);
	}
}
