package modi.backend.application.exhibition.list;

import modi.backend.application.exhibition.ExhibitionCriteria;
import modi.backend.application.exhibition.ExhibitionResult;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegionGroup;
import modi.backend.domain.exhibition.catalog.ExhibitionSort;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;
import modi.backend.support.response.Cursor;
import modi.backend.support.time.AppTime;

/**
 * 전시 <b>목록/탐색</b> 유스케이스(5.2)와 탐색 필터 메타(지역 그룹).
 *
 * <p>흐름은 [조건 조립({@link ExhibitionSearchQueryFactory}) → 키셋 슬라이스 → 조립({@link ExhibitionListAssembler})]
 * 세 박자다. 거리순만 예외로, 좌표가 전시장에 있어 DB가 정렬하지 못하므로 후보를 읽어 앱에서 세운다
 * (P2 best-effort — 데이터가 늘면 이 경로만 선형으로 비싸진다).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionListService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;

	private final ExhibitionQueryRepository exhibitionQueryRepository;
	private final ExhibitionSearchQueryFactory queryFactory;
	private final ExhibitionListAssembler listAssembler;

	/** 지역 필터 그룹 목록(디자인 병합 칩). 정적 enum 메타데이터라 조회 없이 변환만 한다. */
	public List<ExhibitionResult.RegionGroup> getRegionGroups() {
		return ExhibitionRegionGroup.all().stream().map(ExhibitionResult.RegionGroup::from).toList();
	}

	/** 목록/탐색. 필터 미지정 시 오늘 진행 중인 전시를 기본 노출한다. 비로그인은 CATALOG만. */
	@Transactional(readOnly = true)
	public ExhibitionResult.ListPage search(ExhibitionCriteria.Search criteria) {
		LocalDate today = LocalDate.now(AppTime.KST);
		ExhibitionSort sort = ExhibitionSort.from(criteria.sort());
		int size = clampSize(criteria.size());

		if (sort == ExhibitionSort.DISTANCE) {
			return searchByDistance(criteria, today, size, queryFactory.create(criteria, sort, today, null, null));
		}

		Cursor cursor = Cursor.decode(criteria.cursor(), sort.code()).orElse(null);
		ExhibitionQuery query = queryFactory.create(criteria, sort, today,
				cursor == null ? null : cursor.key(), cursor == null ? null : cursor.lastId());

		List<Exhibition> rows = exhibitionQueryRepository.searchSlice(query, size + 1);
		boolean hasNext = rows.size() > size;
		List<Exhibition> page = hasNext ? rows.subList(0, size) : rows;

		List<ExhibitionResult.ListItem> content = listAssembler.assemble(page, today, criteria.requesterId());
		String nextCursor = hasNext ? encodeCursor(sort, page.get(page.size() - 1)) : null;
		return new ExhibitionResult.ListPage(content, nextCursor, hasNext, exhibitionQueryRepository.count(query));
	}

	/**
	 * 거리순(P2 best-effort). lat·lng 필수. 좌표는 전시장에서 오므로 후보의 장소를 배치 로드해 단순 제곱거리로
	 * 정렬(좌표 null은 뒤로)하고, 커서는 마지막 id 위치 기준으로 슬라이스한다.
	 */
	private ExhibitionResult.ListPage searchByDistance(ExhibitionCriteria.Search criteria, LocalDate today,
			int size, ExhibitionQuery query) {
		if (criteria.lat() == null || criteria.lng() == null) {
			throw new CoreException(ErrorType.INVALID_INPUT, "거리순 정렬은 lat·lng가 필요합니다.");
		}
		double lat = criteria.lat();
		double lng = criteria.lng();
		List<Exhibition> candidates = exhibitionQueryRepository.searchAll(query);
		Map<Long, ExhibitionPlace> placesById = listAssembler.placesById(candidates);
		List<Exhibition> ordered = candidates.stream().sorted(distanceComparator(placesById, lat, lng)).toList();

		Cursor cursor = Cursor.decode(criteria.cursor(), ExhibitionSort.DISTANCE.code()).orElse(null);
		int start = cursor == null ? 0 : nextIndexAfter(ordered, cursor.lastId());
		int end = Math.min(start + size, ordered.size());
		List<Exhibition> page = start >= ordered.size() ? List.of() : ordered.subList(start, end);
		boolean hasNext = end < ordered.size();

		List<ExhibitionResult.ListItem> content = listAssembler.assemble(page, today, criteria.requesterId());
		String nextCursor = null;
		if (hasNext) {
			Exhibition last = page.get(page.size() - 1);
			nextCursor = Cursor.of(ExhibitionSort.DISTANCE.code(),
					String.valueOf(distanceSq(placesById.get(last.getExhibitionPlaceId()), lat, lng)), last.getId())
					.encode();
		}
		return new ExhibitionResult.ListPage(content, nextCursor, hasNext, ordered.size());
	}

	private static String encodeCursor(ExhibitionSort sort, Exhibition last) {
		return Cursor.of(sort.code(), sort.cursorKeyOf(last), last.getId()).encode();
	}

	private static Comparator<Exhibition> distanceComparator(Map<Long, ExhibitionPlace> placesById, double lat,
			double lng) {
		return Comparator.comparingDouble((Exhibition e) -> distanceSq(placesById.get(e.getExhibitionPlaceId()), lat,
				lng)).thenComparing(Exhibition::getId);
	}

	/** 단순 제곱 유클리드 거리(좌표 null은 최댓값 → 뒤로). 좌표는 전시장에서 온다. */
	private static double distanceSq(ExhibitionPlace place, double lat, double lng) {
		if (place == null || place.getGpsX() == null || place.getGpsY() == null) {
			return Double.MAX_VALUE;
		}
		double dx = place.getGpsX() - lng;
		double dy = place.getGpsY() - lat;
		return dx * dx + dy * dy;
	}

	private static int nextIndexAfter(List<Exhibition> ordered, Long lastId) {
		for (int i = 0; i < ordered.size(); i++) {
			if (ordered.get(i).getId().equals(lastId)) {
				return i + 1;
			}
		}
		return 0;
	}

	private static int clampSize(Integer size) {
		if (size == null || size < 1) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}
}
