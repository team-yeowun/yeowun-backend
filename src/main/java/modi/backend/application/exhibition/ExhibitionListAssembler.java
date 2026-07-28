package modi.backend.application.exhibition;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;

/**
 * 전시 페이지 → 목록 항목 조립 컴포넌트. 애그리거트가 쪼개져 있어 장소(N:1)·가격(1:1)·관심 여부를 각각 읽어야 하는데,
 * <b>전부 배치 조회</b>로 묶어 쿼리 수가 페이지 크기에 비례하지 않게 한다(N+1 방지).
 *
 * <p>가격은 상세 엔티티를 통째로 읽지 않고 값만 뽑는다 — {@code description}이 평균 1KB가 넘어 목록에서는
 * 읽고 버리는 비용이 크다.
 */
@Component
@RequiredArgsConstructor
public class ExhibitionListAssembler {

	private final ExhibitionRepository exhibitionRepository;
	private final ExhibitionPlaceRepository exhibitionPlaceRepository;
	private final ExhibitionBookmarkRepository exhibitionBookmarkRepository;

	/** 페이지를 목록 항목으로 조립한다. {@code requesterId}가 null이면 관심 여부는 전부 false. */
	public List<ExhibitionResult.ListItem> assemble(List<Exhibition> page, LocalDate today, Long requesterId) {
		if (page.isEmpty()) {
			return List.of();
		}
		Map<Long, ExhibitionPlace> placesById = placesById(page);
		Map<Long, String> pricesById = exhibitionRepository
				.findPricesByExhibitionIds(page.stream().map(Exhibition::getId).toList());
		Set<Long> bookmarked = bookmarkedIds(requesterId, page);
		return page.stream().map(e -> ExhibitionResult.ListItem.from(e, placesById.get(e.getExhibitionPlaceId()),
				today, Exhibition.isFree(pricesById.get(e.getId())), bookmarked.contains(e.getId()))).toList();
	}

    /** 전시들의 전시장을 배치 조회한다(거리순 정렬도 좌표가 필요해 이 결과를 쓴다). */
	public Map<Long, ExhibitionPlace> placesById(List<Exhibition> exhibitions) {
		Set<Long> placeIds = exhibitions.stream().map(Exhibition::getExhibitionPlaceId).collect(Collectors.toSet());
		return exhibitionPlaceRepository.findAllByIds(placeIds).stream()
				.collect(Collectors.toMap(ExhibitionPlace::getId, p -> p, (a, b) -> a));
	}

	private Set<Long> bookmarkedIds(Long requesterId, List<Exhibition> page) {
		if (requesterId == null || page.isEmpty()) {
			return Set.of();
		}
		return exhibitionBookmarkRepository.findBookmarkedExhibitionIds(requesterId,
				page.stream().map(Exhibition::getId).toList());
	}
}
