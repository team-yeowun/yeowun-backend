package modi.backend.ingestion.domain.data;

import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;

import java.util.List;

/**
 * 목록 수집 1회의 결과(도메인 포트 출력) — 아이템들 + 원천이 말한 총 건수.
 *
 * <p><b>왜 List가 아니라 이 record인가</b>: {@code ingestion_run.total_count}를 채울 수 없기 때문이다.
 * 원천이 말한 총 건수는 <b>응답에만</b> 있고 어댑터 안에서 파싱되고 버려졌다 — 포트가 아이템 목록만 돌려주면
 * 감사 테이블의 컬럼을 채울 수 없다. 포트가 정보를 숨기고 있는 것이고, 그때 포트를 넓히는 건 설계의 요구다
 * (2단계 {@code GenreClassifier}, 4단계 {@code PlaceHoursProvider.vendor()}와 같은 판단).
 *
 * <p><b>절단 플래그는 두지 않는다</b>: 이 수집의 목표는 <b>신규 등록 포착</b>이지 전량 정합이 아니다(사용자 결정).
 * {@code max-items}는 폭주 방지 상한으로 남지만, 상한에 걸린 사실을 배치 단위로 보고하지는 않는다.
 *
 * @param items      적재 가능한 수집 데이터.
 * @param totalCount 원천이 말한 총 건수. 호출 자체가 없었으면(인증키 미설정) null = <b>"모른다"</b>(0이 아니다).
 */
public record CatalogListData(List<CatalogExhibitionData> items, Integer totalCount) {

	/** 외부 호출을 하지 않았을 때(인증키 미설정) — 아무것도 모른다. */
	public static CatalogListData none() {
		return new CatalogListData(List.of(), null);
	}
}
