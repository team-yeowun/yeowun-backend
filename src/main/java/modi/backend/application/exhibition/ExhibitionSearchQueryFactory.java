package modi.backend.application.exhibition;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionSection;
import modi.backend.domain.exhibition.catalog.ExhibitionSort;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * 탐색 입력({@link ExhibitionCriteria.Search}) → 조회 조건({@link ExhibitionQuery}) 변환 컴포넌트.
 * 원시 문자열 파싱·기간 조건 판정·섹션 날짜창 계산이 모두 여기 모인다.
 *
 * <p>서비스에서 떼어낸 이유는 <b>순수 함수</b>이기 때문이다 — 리포지토리를 모르고 입력만 보므로, 단위 테스트가
 * "이 입력에 이 조건이 서는가"만 보면 된다. 조회 서비스는 이 결과를 받아 슬라이스·조립에만 집중한다.
 */
@Component
public class ExhibitionSearchQueryFactory {

	private static final int ENDING_SOON_DAYS = 7;

	/** 탐색 입력을 조회 조건으로 옮긴다. {@code cursorKey}·{@code cursorId}가 null이면 첫 페이지(경계 없음). */
	public ExhibitionQuery create(ExhibitionCriteria.Search criteria, ExhibitionSort sort, LocalDate today,
			String cursorKey, Long cursorId) {
		String keyword = normalizeKeyword(criteria.keyword());
		ExhibitionSection section = ExhibitionSection.from(criteria.section());
		LocalDate ongoingOn = resolveOngoingOn(criteria.date(), keyword, section, today);
		LocalDate notEndedOn = resolveNotEndedOn(ongoingOn, keyword, today);
		LocalDate from = null;
		LocalDate to = null;
		if (section == ExhibitionSection.ENDING_SOON) {
			from = today;
			to = today.plusDays(ENDING_SOON_DAYS);
		} else if (section == ExhibitionSection.OPENING_THIS_MONTH) {
			if ("week".equalsIgnoreCase(criteria.period() == null ? "" : criteria.period().trim())) {
				from = today.with(DayOfWeek.MONDAY);
				to = today.with(DayOfWeek.SUNDAY);
			} else {
				from = today.withDayOfMonth(1);
				to = today.with(TemporalAdjusters.lastDayOfMonth());
			}
		}
		return new ExhibitionQuery(keyword, ongoingOn, notEndedOn, parseRegions(criteria.region()),
				parseCategories(criteria.category()), section, from, to, sort, cursorKey, cursorId,
				criteria.requesterId());
	}

	/**
	 * 이 조회에 "진행 중" 기간 조건을 걸지 결정한다(null이면 기간 무관).
	 *
	 * <p>기준은 <b>사용자가 무엇을 기대하는가</b>다 — 목록을 좁히는 필터(지역·카테고리)는 기본 목록과 같은 기간을
	 * 유지하고, 스스로 기간 의미를 갖는 섹션과 "아는 것을 찾는" 검색만 예외로 둔다.
	 */
	private LocalDate resolveOngoingOn(LocalDate date, String keyword, ExhibitionSection section, LocalDate today) {
		if (date != null) {
			return date;
		}
		if (section != null) {
			// 섹션은 스스로 기간의 의미를 갖는다 — "지금 볼 수 있는"(곧 끝남·무료)이면 진행 중,
			// "이번 달 새로 열리는"이면 아직 열리지 않은 전시를 보여주는 것이 목적이라 기간 조건을 걸지 않는다.
			return section.requiresOngoing() ? today : null;
		}
		if (keyword != null && !keyword.isBlank()) {
			// 검색은 "목록 좁히기"가 아니라 "아는 것 찾기"라 진행 중으로 묶지 않는다 —
			// 다음 달 개막 전시도 이름으로 찾을 수 있어야 한다. 대신 끝난 전시는 내보내지 않는다(notEndedOn).
			return null;
		}
		// 지역·카테고리는 목록을 좁히는 필터일 뿐이라 기본(필터 없음)과 같은 기간 조건을 유지한다 —
		// 필터를 걸었다고 다른 목록(미래·종료 전시)으로 바뀌면 안 된다.
		return today;
	}

	/**
	 * 검색에만 거는 "아직 끝나지 않음" 조건. 진행 중 조건이 이미 걸렸다면 불필요하다.
	 * 검색은 아직 열지 않은 전시를 찾을 수 있어야 하지만, <b>이미 끝난 전시까지 보여줄 이유는 없다</b>.
	 */
	private LocalDate resolveNotEndedOn(LocalDate ongoingOn, String keyword, LocalDate today) {
		if (ongoingOn != null || keyword == null || keyword.isBlank()) {
			return null;
		}
		return today;
	}

	private static String normalizeKeyword(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.length() < 2) {
			throw new CoreException(ErrorType.INVALID_INPUT, "검색어는 최소 2글자여야 합니다: " + raw);
		}
		return trimmed;
	}

	private static List<ExhibitionRegion> parseRegions(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.map(ExhibitionRegion::from).toList();
	}

	private static List<ExhibitionCategory> parseCategories(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.map(ExhibitionCategory::from).toList();
	}
}
