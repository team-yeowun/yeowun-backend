package modi.backend.application.exhibition.cache;


import java.util.Optional;
import modi.backend.application.exhibition.ExhibitionCriteria;
import modi.backend.domain.exhibition.catalog.ExhibitionSection;
import modi.backend.domain.exhibition.catalog.ExhibitionSort;
import modi.backend.support.cache.MyCache;

/**
 * - 목록 요청 → 이 요청을 서빙할 캐시 선언 - 대상이 아니면 빈 값을 돌려주고, 호출부는 그때 DB로 감
 * <p>
 * - 필터 없는 첫 페이지만 캐시 - 검색어·지역·카테고리·기간·날짜·좌표가 하나라도 붙으면 캐시하지 않음 - 커서가 있으면(2페이지 이후) 캐시하지 않음 - 조합을 다 캐시하면 키가 폭발하고, 정작 각 키의
 * 히트율은 떨어짐 - 홈·탐색의 첫 화면이 전체 트래픽의 대부분이라 이 일곱 개로 충분
 * <p>
 * - 요청이 그 "첫 화면" 모양인지는 {@link ExhibitionCriteria.Search#isPlainFirstPage()}가 스스로 판단 - 여기 남은 책임은 (섹션 × 정렬) → 캐시 선언 하나뿐
 */
public final class ExhibitionListCacheResolver {

    private ExhibitionListCacheResolver() {
    }

    public static Optional<MyCache> resolve(ExhibitionCriteria.Search criteria) {
        if (!criteria.isPlainFirstPage()) {
            return Optional.empty();
        }

        ExhibitionSection section = ExhibitionSection.from(criteria.section());
        ExhibitionSort sort = ExhibitionSort.from(criteria.sort());

        return section == null ? explore(sort) : home(section, sort);
    }

    /**
     * - 홈 섹션은 최신순 한 벌만 캐시 - 섹션 자체가 이미 큐레이션이라 정렬을 바꿔 보는 트래픽이 거의 없음
     * <p>
     * - {@code default}를 두지 않음 - 섹션이 추가되면 컴파일이 깨져 여기서 결정을 요구 - "섹션이면 일단 캐시 안 함"으로 흘려보내면 유령 캐시가 조용히 생김 - 유령 캐시 = 선언·워밍만
     * 되고 조회에서는 영영 찾지 않는 캐시
     */
    private static Optional<MyCache> home(ExhibitionSection section, ExhibitionSort sort) {
        if (sort != ExhibitionSort.LATEST) {
            return Optional.empty();
        }
        return Optional.of(switch (section) {
            case ENDING_SOON -> ExhibitionCache.HomeEndingSoon.INSTANCE;
            case OPENING_THIS_MONTH -> ExhibitionCache.HomeNewThisMonth.INSTANCE;
            case FREE -> ExhibitionCache.HomeFree.INSTANCE;
        });
    }

    /**
     * - 섹션 없는 탐색 첫 페이지 - 정렬 축이 곧 캐시
     */
    private static Optional<MyCache> explore(ExhibitionSort sort) {
        return switch (sort) {
            case LATEST -> Optional.of(ExhibitionCache.ExploreLatestP1.INSTANCE);
            case ENDING -> Optional.of(ExhibitionCache.ExploreEndingP1.INSTANCE);
            case POPULAR -> Optional.of(ExhibitionCache.ExplorePopularP1.INSTANCE);
            case DISTANCE -> Optional.empty();      // 좌표 기준이라 사용자마다 결과가 다르다
        };
    }

}
