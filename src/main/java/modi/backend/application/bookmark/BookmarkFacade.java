package modi.backend.application.bookmark;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.response.Cursor;
import modi.backend.support.time.AppTime;

/**
 * 관심 전시(북마크) 유스케이스 조율(북마크 6.1~6.3). load·조율·save만 한다.
 * 토글은 멱등이며(add/remove가 멱등), 전시 존재 검증 후 위임한다. 목록은 활성 북마크 id를 모아 전시를 벌크 로드하고
 * sort에 맞게 앱 레이어에서 정렬·커서 슬라이스한다(북마크 수가 적어 인메모리 키셋으로 충분).
 */
@Service
@RequiredArgsConstructor
public class BookmarkFacade {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;

	private final ExhibitionBookmarkRepository exhibitionBookmarkRepository;
	private final ExhibitionRepository exhibitionRepository;
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;

	/** 관심 등록(6.1, 멱등). 없는 전시면 404. 반환은 항상 bookmarked=true. */
	@Transactional
	public BookmarkResult.Toggle add(BookmarkCriteria.Toggle criteria) {
		ensureExhibitionExists(criteria.exhibitionId());
		exhibitionBookmarkRepository.add(criteria.userId(), criteria.exhibitionId());
		return new BookmarkResult.Toggle(criteria.exhibitionId(), true);
	}

	/** 관심 해제(6.2, 멱등). 없는 전시면 404. 반환은 항상 bookmarked=false. */
	@Transactional
	public BookmarkResult.Toggle remove(BookmarkCriteria.Toggle criteria) {
		ensureExhibitionExists(criteria.exhibitionId());
		exhibitionBookmarkRepository.remove(criteria.userId(), criteria.exhibitionId());
		return new BookmarkResult.Toggle(criteria.exhibitionId(), false);
	}

	/**
	 * 관심 전시 목록(6.3). sort=latest는 등록 최신순, sort=ending은 종료 임박순(종료일 asc, null 뒤로).
	 * 커서는 정렬 판별자(sort)를 검증하며(불일치 → INVALID_CURSOR), 마지막 항목 id 위치 기준으로 슬라이스한다.
	 */
	@Transactional(readOnly = true)
	public BookmarkResult.ListPage list(BookmarkCriteria.List criteria) {
		LocalDate today = LocalDate.now(AppTime.KST);
		String sort = canonicalSort(criteria.sort());
		int size = clampSize(criteria.size());
		Cursor cursor = Cursor.decode(criteria.cursor(), sort).orElse(null);
		Long cursorId = cursor == null ? null : cursor.lastId();

		List<Exhibition> rows = "ending".equals(sort)
				? endingPage(criteria.userId(), cursor, cursorId, size + 1)
				: registeredPage(criteria.userId(), cursorId, size + 1);
		boolean hasNext = rows.size() > size;
		List<Exhibition> page = hasNext ? rows.subList(0, size) : rows;

		List<ExhibitionResult.ListItem> content = toListItems(page, today);
		String nextCursor = hasNext ? encodeCursor(sort, page.get(page.size() - 1)) : null;
		long totalCount = exhibitionRepository.countActiveByIds(activeExhibitionIds(criteria.userId()));
		return new BookmarkResult.ListPage(content, nextCursor, hasNext, totalCount);
	}

	/**
	 * 등록 최신순 한 페이지 — 정렬 키(북마크 createdAt·id)가 북마크 테이블 자기 컬럼이라 <b>거기서 잘라 온다</b>.
	 * 전시는 그 페이지분만 읽는다(예전엔 북마크 전량의 전시를 읽어 앱에서 잘랐다).
	 */
	private List<Exhibition> registeredPage(Long userId, Long cursorId, int limitPlusOne) {
		List<Long> pageIds = exhibitionBookmarkRepository.findActiveExhibitionIdsPage(userId, cursorId, limitPlusOne);
		if (pageIds.isEmpty()) {
			return List.of();
		}
		Map<Long, Exhibition> byId = exhibitionRepository.findAllActiveByIds(pageIds).stream()
				.collect(Collectors.toMap(Exhibition::getId, e -> e, (a, b) -> a));
		// 등록순은 북마크 쪽 순서가 진실이다 — 조회 순서에 기대지 않고 id 순서대로 복원한다.
		// 그새 삭제된 전시는 byId에 없어 자연히 빠진다(그만큼 페이지가 짧아질 뿐 커서는 유효하다).
		return pageIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
	}

	/**
	 * 종료 임박순 한 페이지 — 정렬 키(end_date)가 전시 컬럼이라 북마크 쪽에서 못 자른다.
	 * 대신 정렬·커서 경계·LIMIT을 DB에 맡겨 <b>반환 행만</b> 앱으로 올린다(IN 대상은 크지만 결과는 한 페이지).
	 */
	private List<Exhibition> endingPage(Long userId, Cursor cursor, Long cursorId, int limitPlusOne) {
		List<Long> allIds = activeExhibitionIds(userId);
		if (allIds.isEmpty()) {
			return List.of();
		}
		LocalDate cursorEndDate = cursor == null || cursor.key() == null ? null : LocalDate.parse(cursor.key());
		return exhibitionRepository.findActiveByIdsOrderByEndDate(allIds, cursorEndDate, cursorId, limitPlusOne);
	}

	private List<Long> activeExhibitionIds(Long userId) {
		return exhibitionBookmarkRepository.findActiveExhibitionIdsByUserIdOrderByRegisteredDesc(userId);
	}

	/** 페이지 전시들을 장소·가격 배치 조회로 조립한다(N+1 방지). 관심 목록이므로 bookmarked는 항상 true. */
	private List<ExhibitionResult.ListItem> toListItems(List<Exhibition> page, LocalDate today) {
		if (page.isEmpty()) {
			return List.of();
		}
		Map<Long, ExhibitionPlace> placesById = exhibitionPlaceRepository.findAllByIds(
				page.stream().map(Exhibition::getExhibitionPlaceId).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(ExhibitionPlace::getId, p -> p, (a, b) -> a));
		Map<Long, String> pricesById = exhibitionRepository
				.findPricesByExhibitionIds(page.stream().map(Exhibition::getId).toList());
		return page.stream()
				.map(e -> ExhibitionResult.ListItem.from(e, placesById.get(e.getExhibitionPlaceId()), today,
						Exhibition.isFree(pricesById.get(e.getId())), true))
				.toList();
	}

	private static String encodeCursor(String sort, Exhibition last) {
		String key = "ending".equals(sort) && last.getEndDate() != null ? last.getEndDate().toString() : null;
		return Cursor.of(sort, key, last.getId()).encode();
	}

	private void ensureExhibitionExists(Long exhibitionId) {
		if (exhibitionRepository.findById(exhibitionId).isEmpty()) {
			throw new CoreException(ExhibitionErrorCode.EXHIBITION_NOT_FOUND);
		}
	}

	/** sort 코드 → 정규화(latest 기본). 미정의 값은 latest로 취급(커서 정렬 판별자도 이 값으로 통일). */
	private static String canonicalSort(String sort) {
		if (sort == null) {
			return "latest";
		}
		return "ending".equalsIgnoreCase(sort.trim()) ? "ending" : "latest";
	}

	private static int clampSize(Integer size) {
		if (size == null || size < 1) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}
}
