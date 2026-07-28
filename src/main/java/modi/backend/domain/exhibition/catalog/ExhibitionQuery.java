package modi.backend.domain.exhibition.catalog;

import java.time.LocalDate;
import java.util.List;

/**
 * 전시 목록/탐색 조회 조건(도메인 포트 입력). (03_전시.md 5.2)
 * 커서 페이지네이션(키셋)용으로 정렬 판별자와 커서 경계값을 함께 싣는다.
 *
 * @param keyword     전시명·전시장명 부분 일치(null이면 미적용)
 * @param ongoingOn   해당 날짜에 진행 중(startDate ≤ ongoingOn ≤ endDate)인 전시만(null이면 미적용)
 * @param notEndedOn  해당 날짜에 아직 끝나지 않은(endDate ≥ notEndedOn) 전시만(null이면 미적용).
 *                    시작일은 보지 않으므로 <b>아직 열지 않은 전시는 포함</b>한다 — 검색 전용 조건이다.
 * @param regions     지역 다중 필터(빈 리스트면 미적용, IN 조건)
 * @param categories  카테고리 다중 필터(빈 리스트면 미적용, IN 조건)
 * @param section     섹션 필터(null이면 미적용). ending-soon/opening-this-month는 {@code sectionFrom~sectionTo} 창을 함께 쓴다.
 * @param sectionFrom 섹션 날짜창 시작(section이 날짜기반일 때만, 아니면 null)
 * @param sectionTo   섹션 날짜창 끝(section이 날짜기반일 때만, 아니면 null)
 * @param sort        정렬 축. 키셋 ORDER BY와 커서 경계가 이 하나를 함께 본다.
 * @param cursorKey   커서 정렬값(문자열 표현, 날짜/조회수 등). null이면 정렬 컬럼값이 null인 경계이거나 커서 없음.
 * @param cursorId    커서 마지막 id(최종 타이브레이커). null이면 커서 없음(첫 페이지).
 * @param requesterId 요청자 id — CUSTOM 노출 판단용. null이면 CATALOG만 노출한다.
 */
public record ExhibitionQuery(
		String keyword,
		LocalDate ongoingOn,
		LocalDate notEndedOn,
		List<ExhibitionRegion> regions,
		List<ExhibitionCategory> categories,
		ExhibitionSection section,
		LocalDate sectionFrom,
		LocalDate sectionTo,
		ExhibitionSort sort,
		String cursorKey,
		Long cursorId,
		Long requesterId) {

	/** 필터 없는 전체 조회(부팅 시더 프로브 등 내부용). */
	public static ExhibitionQuery unfiltered() {
		return new ExhibitionQuery(null, null, null, List.of(), List.of(), null, null, null, ExhibitionSort.LATEST,
				null, null, null);
	}
}
