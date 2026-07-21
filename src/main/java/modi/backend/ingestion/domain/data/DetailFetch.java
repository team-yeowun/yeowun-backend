package modi.backend.ingestion.domain.data;

import modi.backend.domain.exhibition.catalog.CatalogDetailData;

/**
 * 상세(detail2) 조회 결과 한 벌 — 도메인 값({@link CatalogDetailData}, 변환 후)과 벤더 원문
 * ({@link CatalogDetailVendorItem}, verbatim)을 함께 나른다. 도메인 반영과 스냅샷 적재가 같은 응답에서 나와야
 * 짝이 밀리는 오염이 원인부터 불가능하다(ADR-13).
 */
public record DetailFetch(CatalogDetailData data, CatalogDetailVendorItem vendor) {
}
