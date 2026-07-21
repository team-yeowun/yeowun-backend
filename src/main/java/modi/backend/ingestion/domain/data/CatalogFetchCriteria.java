package modi.backend.ingestion.domain.data;

import modi.backend.ingestion.domain.CatalogServiceType;
import modi.backend.ingestion.domain.CatalogSortOrder;
import modi.backend.ingestion.domain.ExhibitionRealm;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 목록 수집 1회의 요청 조건 — <b>"무엇을 얼마나 가져올지"는 호출자(수집 도메인)가 정하고</b>, 어댑터는 그것을
 * 원천의 요청 파라미터로 옮기기만 한다.
 *
 * <p><b>왜 어댑터 설정이 아니라 포트 인자인가</b>: 인프라는 외부와 맞닿아 <i>대신 가져다주는</i> 계층이지
 * 수집 범위를 정하는 계층이 아니다. 어댑터가 상한을 소유하면 "이번 실행에 얼마나 가져올까"를 도메인이 결정할 수 없다.
 * 상세 조회({@code fetchDetailSnapshot(externalId)})는 이미 요청 변수를 호출자에게서 받는다 — 목록만 예외였다.
 * 접속 정보(base URL·인증키·타임아웃)는 반대로 순수 인프라 관심사라 어댑터 설정에 남는다.
 *
 * <p>상한을 <b>건수로</b> 표현하는 것도 같은 이유다. "5페이지"는 페이지 크기를 알아야 의미가 생기는 전송 단위라
 * 도메인이 말할 수 있는 문장이 아니다. 몇 번 나눠 부를지는 어댑터가 {@code pageSize}로 유도한다.
 *
 * @param realm       수집 대상 분야. 벤더 코드가 아니라 개념이다.
 * @param serviceType 분야별 구분(필수). 원천 파라미터 {@code serviceTp}.
 * @param sortOrder   정렬 기준(필수). 원천 파라미터 {@code sortStdr}.
 * @param pageSize    한 번의 호출로 받을 행 수. 결과 데이터가 아니라 호출 횟수를 좌우한다.
 * @param maxItems    이번 실행에서 수집할 최대 건수(폭주 방지 상한). 원천에 더 있는데 이 상한에 걸리면
 *                  {@link CatalogListData#truncated()}로 보고된다.
 * @param filter      선택 필터(지역·기간·장소·검색어·좌표·정렬). null이면 {@link CatalogFetchFilter#none()}.
 *                  값이 없는 항목은 원천 요청에 <b>파라미터 자체가 실리지 않는다.</b>
 */
public record CatalogFetchCriteria(ExhibitionRealm realm, CatalogServiceType serviceType,
		CatalogSortOrder sortOrder, int pageSize, int maxItems, CatalogFetchFilter filter) {

	/**
	 * 필터 없는 기본 수집 — 분야별 구분은 공연/전시, 정렬은 시작일 오름차순으로 둔다.
	 * <p>이 정렬은 원천이 정렬을 지정하지 않았을 때와 결과가 같아, 기본값으로 두어도 동작이 바뀌지 않는다.
	 */
	public static CatalogFetchCriteria of(ExhibitionRealm realm, int pageSize, int maxItems) {
		return new CatalogFetchCriteria(realm, CatalogServiceType.PERFORMANCE_EXHIBITION,
				CatalogSortOrder.START_DATE_ASC, pageSize, maxItems, CatalogFetchFilter.none());
	}

	public CatalogFetchCriteria {
		if (filter == null) {
			filter = CatalogFetchFilter.none();
		}
		if (realm == null) {
			throw new CoreException(ErrorType.INVALID_INPUT, "수집 대상 분야는 필수입니다");
		}
		// 원천이 요구하는 필수 파라미터라 비워둘 수 없다 — 기본값이 필요하면 of(...)를 쓴다.
		if (serviceType == null) {
			throw new CoreException(ErrorType.INVALID_INPUT, "분야별 구분은 필수입니다");
		}
		if (sortOrder == null) {
			throw new CoreException(ErrorType.INVALID_INPUT, "정렬 기준은 필수입니다");
		}
		if (pageSize <= 0) {
			throw new CoreException(ErrorType.INVALID_INPUT, "페이지 크기는 1 이상이어야 합니다: " + pageSize);
		}
		if (maxItems <= 0) {
			throw new CoreException(ErrorType.INVALID_INPUT, "수집 상한은 1 이상이어야 합니다: " + maxItems);
		}
	}

	/**
	 * 상한을 채우는 데 필요한 호출 횟수 — 건수 상한을 전송 단위로 옮긴다(올림).
	 * 예: 500건 / 100행 = 5회, 501건 / 100행 = 6회.
	 */
	public int maxCalls() {
		return (maxItems + pageSize - 1) / pageSize;
	}
}
