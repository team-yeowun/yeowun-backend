package modi.backend.ingestion.domain.data;

import java.util.List;

/**
 * 목록 <b>한 페이지</b>의 수집 결과 — 어댑터가 응답을 도메인 어휘로 옮긴 것.
 *
 * <p><b>{@code items}는 필터 이전</b>이다(적재 불가 행도 들어 있다). 두 가지가 이 수에 달려 있기 때문이다:
 * <ul>
 *   <li><b>마지막 페이지 판정</b> — {@code items.size() < pageSize}면 마지막이다. 어댑터가 미리 걸러 주면
 *       불량 행이 낀 <b>꽉 찬 페이지</b>가 "덜 찬 페이지"로 보여 순회가 조기에 끊긴다.</li>
 *   <li><b>조기 종료 판정</b> — 걸러진 행의 식별자도 "이미 아는 것"에 포함돼야 한다. 빼면 불량 행이 낀 페이지는
 *       "전량 known"이 영영 성립하지 않아 조기 종료가 죽는다.</li>
 * </ul>
 * 적재 가능 여부({@link CatalogExhibitionData#isPersistable()})는 호출부가 거른다.
 *
 * @param items      이 페이지의 수집 데이터(응답 순서 그대로, 필터 이전)
 * @param totalCount 원천이 말한 총 건수. 응답에 없으면 null = <b>"모른다"</b>(0이 아니다)
 */
public record CatalogPage(List<CatalogExhibitionData> items, Integer totalCount) {

	/** 외부 호출을 하지 않았을 때(인증키 미설정) — 아무것도 모른다. */
	public static CatalogPage none() {
		return new CatalogPage(List.of(), null);
	}

	/** 원천이 요청한 만큼 채워 줬는가 — 아니면 여기가 마지막 페이지다(원천이 마지막을 명시하지 않는다). */
	public boolean isFull(int pageSize) {
		return items.size() >= pageSize;
	}

	/** 이 페이지가 원천에서 받은 식별자 — 조기 종료 판정용(필터 이전). */
	public List<String> externalIds() {
		return items.stream().map(CatalogExhibitionData::externalId).filter(java.util.Objects::nonNull).toList();
	}
}
