package modi.backend.ingestion.domain.data;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 문화포털 <b>상세(detail2)</b> 응답 아이템의 원문 verbatim 어휘(ADR-13) — 도메인 변환(디코드·평문 추출·타입 정제)
 * <b>이전</b> 값이다. {@code culture_detail_snapshot}이 이 필드들을 응답 구조 그대로 적재한다.
 * <p>
 * 특히 {@code contents1}의 워드프레스 HTML 원문이 보존되어, 평문 추출 규칙이 바뀌면 재조회 없이 여기서 다시 뽑을 수 있다.
 * <p>
 * 목록({@link CatalogListVendorItem})과 <b>나눠 선언한다</b>. detail2가 실제로 주는 18태그만 담는다
 * (2026-07-21 표본 확인 · 기존 60건 전수 집계와 일치) — 목록 전용 {@code thumbnail}·{@code serviceName}은 없다.
 */
public record CatalogDetailVendorItem(
		String seq,
		String title,
		String startDate,
		String endDate,
		String place,
		String realmName,
		String area,
		String sigungu,
		String gpsX,
		String gpsY,
		String price,
		String contents,
		String url,
		String phone,
		String imgUrl,
		String placeUrl,
		String placeAddr,
		String placeSeq) {

	/**
	 * 변경 감지 해시의 원료 — 필드를 고정 순서로 이어붙인 정준 문자열(null은 빈 값).
	 * <p>
	 * <b>순서를 바꾸면 기존 행이 전부 "변경됨"으로 판정</b>된다 — 필드를 더할 때는 끝에 붙인다.
	 */
	public String canonical() {
		return Stream.of(seq, title, startDate, endDate, place, realmName, area, sigungu, gpsX, gpsY,
						price, contents, url, phone, imgUrl, placeUrl, placeAddr, placeSeq)
				.map(v -> v == null ? "" : v)
				.collect(Collectors.joining(""));
	}
}
