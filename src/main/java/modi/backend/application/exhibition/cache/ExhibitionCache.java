package modi.backend.application.exhibition.cache;


import java.time.Duration;
import java.util.List;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.support.cache.MyCache;

/**
 * - 전시에서 사용하는 캐시 선언 7종
 *   - 캐시 이름은 클래스 이름에서 결정됨
 *   - 예: {@code HomeBanners} 선언 → {@code "HomeBanners"}라는 캐시 이름
 *
 * - TTL은 캐시 워밍 주기에 맞춰 설정
 *   - 목록 캐시는 6시간마다 워밍
 *   - L2 TTL은 7시간으로 설정
 *   - 워밍 주기 6시간 + 1시간의 여유 시간
 *   - 워밍이 한 번 실패해도 다음 워밍 주기까지 기존 값을 계속 서빙
 *   - 두 번 연속 실패하면 캐시가 만료되고 Lazy Loading이 처리
 *
 * - 상세 캐시는 이벤트 기반 {@code evict}를 주된 무효화 수단으로 사용
 *   - 따라서 목록 캐시보다 TTL을 짧게 설정
 *
 * - {@code enum.values()} 대신 명시적인 캐시 선언 목록을 사용
 *   - 캐시 선언을 추가할 경우 이 목록에도 반드시 추가해야 함
 */
public final class ExhibitionCache {

    /** 조립(CacheConfig)이 순회할 전체 선언 목록. */
    public static final List<MyCache> ALL = List.of(
            HomeBanners.INSTANCE, HomeEndingSoon.INSTANCE, HomeFree.INSTANCE, HomeNewThisMonth.INSTANCE,
            ExploreLatestP1.INSTANCE, ExploreEndingP1.INSTANCE, ExplorePopularP1.INSTANCE,
            ExhibitionDetail.INSTANCE);

    private ExhibitionCache() {
    }

    /**
     * 홈 화면
     */
    public static final class HomeBanners extends MyCache.TwoTierCache {
        public static final HomeBanners INSTANCE = new HomeBanners();

        private HomeBanners() {
            super("홈 배너 목록", Duration.ofHours(1),Duration.ofHours(7), ExhibitionResult.Banners.class);
        }
    }

    public static final class HomeEndingSoon extends MyCache.TwoTierCache {
        public static final HomeEndingSoon INSTANCE = new HomeEndingSoon();

        private HomeEndingSoon() {
            super("곧 끝나는 전시", Duration.ofHours(1),Duration.ofHours(7), ExhibitionResult.ListPage.class);
        }
    }

    public static final class HomeNewThisMonth extends MyCache.TwoTierCache {
        public static final HomeNewThisMonth INSTANCE = new HomeNewThisMonth();

        private HomeNewThisMonth() {
            super("이번달 신규 전시", Duration.ofHours(1), Duration.ofHours(7), ExhibitionResult.ListPage.class);
        }
    }

    public static final class HomeFree extends MyCache.TwoTierCache {
        public static final HomeFree INSTANCE = new HomeFree();

        private HomeFree() {
            super("무료 전시", Duration.ofHours(1), Duration.ofHours(7), ExhibitionResult.ListPage.class);
        }
    }

    /**
     * 전시 탐색 화면
     */
    public static final class ExploreLatestP1 extends MyCache.TwoTierCache {
        public static final ExploreLatestP1 INSTANCE = new ExploreLatestP1();

        private ExploreLatestP1() {
            super("최신순 1페이지", Duration.ofHours(1), Duration.ofHours(7), ExhibitionResult.ListPage.class);
        }
    }

    public static final class ExploreEndingP1 extends MyCache.TwoTierCache {
        public static final ExploreEndingP1 INSTANCE = new ExploreEndingP1();

        private ExploreEndingP1() {
            super("종료순 1페이지", Duration.ofHours(1), Duration.ofHours(7), ExhibitionResult.ListPage.class);
        }
    }

    public static final class ExplorePopularP1 extends MyCache.TwoTierCache {
        public static final ExplorePopularP1 INSTANCE = new ExplorePopularP1();

        private ExplorePopularP1() {
            super("인기순 1페이지", Duration.ofHours(1), Duration.ofHours(7), ExhibitionResult.ListPage.class);
        }
    }

    /**
     * 전시 상세 조회 화면
     * 엔트리 키는 전시 id. 이벤트 evict가 주 수단이라 L1 TTL은 짧게 잡는다.
     */
    public static final class ExhibitionDetail extends MyCache.TwoTierCache {
        public static final ExhibitionDetail INSTANCE = new ExhibitionDetail();

        private ExhibitionDetail() {
            super("전시 상세", Duration.ofMinutes(10), Duration.ofHours(6), ExhibitionResult.Detail.class);
        }
    }

}
