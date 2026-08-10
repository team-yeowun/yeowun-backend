package modi.backend.application.exhibition.list;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import org.springframework.stereotype.Component;

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

    private final ExhibitionPlaceRepository exhibitionPlaceRepository;
    private final ExhibitionBookmarkRepository exhibitionBookmarkRepository;

    /**
     * 페이지를 목록 항목으로 조립한다. {@code requesterId}가 null이면 관심 여부는 전부 false.
     */
    public List<ExhibitionResult.ListItem> assemble(List<Exhibition> page, LocalDate today, Long requesterId) {
        if (page.isEmpty()) {
            return List.of();
        }
        // 조각마다 배치 조회 — 쿼리 수가 페이지 크기에 비례하지 않게 한다.
        // 가격 배치 조회는 없어졌다(V49): free 판정이 전시 행에 굳어 있어 페이지당 쿼리가 하나 준다.
        // 전시장 배치 조회는 남는다 — 지역은 복제본에서 읽지만 <b>전시장 이름</b>은 여전히 조인에서 온다.
        Map<Long, ExhibitionPlace> placesById = placesById(page);
        Set<Long> bookmarked = bookmarkedIds(requesterId, page.stream().map(Exhibition::getId).toList());

        return page.stream().map(e -> ExhibitionResult.ListItem.from(e,
                placesById.getOrDefault(e.getExhibitionPlaceId(), ExhibitionPlace.unknown()), today,
                bookmarked.contains(e.getId()))).toList();
    }

    /**
     * 전시들의 전시장을 배치 조회한다(거리순 정렬도 좌표가 필요해 이 결과를 쓴다).
     */
    public Map<Long, ExhibitionPlace> placesById(List<Exhibition> exhibitions) {
        Set<Long> placeIds = exhibitions.stream().map(Exhibition::getExhibitionPlaceId).collect(Collectors.toSet());
        return exhibitionPlaceRepository.findAllByIds(placeIds).stream()
                .collect(Collectors.toMap(ExhibitionPlace::getId, p -> p, (a, b) -> a));
    }

    /**
     * - 캐시에서 꺼낸 익명 목록에 요청자의 관심 여부를 덮어씀
     *   - 조회는 {@code assemble}과 같은 배치 쿼리 하나
     *   - 캐시가 아껴 준 조회 위에 개인화 비용만 얹는 구조
     *
     * - 비로그인·빈 목록이면 원본을 그대로 돌려줌
     *   - 덮어쓸 것이 없으면 리스트를 다시 만들 이유도 없음
     *
     * - 캐시에 굳은 값을 신뢰하지 않고 항상 요청자 기준으로 덮어씀
     *   - 익명 결과라 전부 false인 것이 정상이지만, true가 섞여 있어도 남의 관심 상태가 새어 나가지 않음
     */
    public List<ExhibitionResult.ListItem> withBookmarks(List<ExhibitionResult.ListItem> items, Long requesterId) {
        if (requesterId == null || items.isEmpty()) {
            return items;
        }
        Set<Long> bookmarked = bookmarkedIds(requesterId,
                items.stream().map(ExhibitionResult.ListItem::exhibitionId).toList());
        return items.stream().map(item -> item.withBookmarked(bookmarked.contains(item.exhibitionId()))).toList();
    }

    /**
     * - 요청자가 관심 등록한 전시 id 집합(배치 1회)
     *   - {@code assemble}(조립 시점)과 {@code withBookmarks}(캐시 덮어쓰기 시점)가 같이 씀
     *   - 두 경로가 같은 조회를 보게 해, 한쪽에만 조건이 붙어 관심 상태가 갈리는 일을 막음
     */
    private Set<Long> bookmarkedIds(Long requesterId, List<Long> exhibitionIds) {
        if (requesterId == null || exhibitionIds.isEmpty()) {
            return Set.of();
        }
        return exhibitionBookmarkRepository.findBookmarkedExhibitionIds(requesterId, exhibitionIds);
    }
}
