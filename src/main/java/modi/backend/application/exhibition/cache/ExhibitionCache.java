package modi.backend.application.exhibition.cache;


import java.time.Duration;
import java.util.List;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.support.cache.MyCache;

/**
 * - 전시에서 사용하는 캐시 선언 8종(목록 7 + 상세 1)
 *   - 캐시 이름은 클래스 이름에서 결정됨
 *   - 예: {@code HomeBanners} 선언 → {@code "HomeBanners"}라는 캐시 이름
 *
 * - 목록은 로컬(L1) + Redis(L2) 2단, 상세는 Redis 하나
 *   - 목록은 약한 정합성을 허용하고 요청이 몰리는 곳이라 서버 안에서 끝내는 L1을 둠
 *   - 상세는 영업시간·가격처럼 DB와 어긋나면 안 되는 값이라 로컬 복사본을 두지 않음
 *   - 로컬 복사본이 없으면 삭제 한 번(Redis)으로 두 서버가 같은 값을 보게 됨
 *
 * - 채우는 방법은 Cache-Aside 하나
 *   - 조회 시 미스면 DB 결과를 적재하고, 쓰기는 DB 커밋 뒤 삭제만 함
 *   - 주기 워밍·무효화 방송(Pub/Sub)은 두지 않음
 *
 * - TTL이 정합성의 마지막 안전장치
 *   - 로컬 10분: 삭제를 처리하지 않은 다른 서버의 L1은 이 시간 안에 만료됨
 *   - Redis 30분: 삭제가 누락된 키도 이 시간 안에 만료됨
 *   - 자정에 '이번 달 신규'·'곧 종료' 기준이 바뀌어도 늦어야 L1 10분 + L2 30분 뒤에는 새 기준이 보임
 *
 * - {@code enum.values()} 대신 명시적인 캐시 선언 목록을 사용
 *   - 캐시 선언을 추가할 경우 이 목록에도 반드시 추가해야 함
 */
public final class ExhibitionCache {

    /** 목록 캐시의 로컬(L1) TTL. 다른 서버의 L1이 삭제 없이 낡을 수 있는 최대 시간이다. */
    public static final Duration LIST_LOCAL_TTL = Duration.ofMinutes(10);

    /** 목록 캐시의 Redis(L2) TTL. 삭제가 누락된 키가 남아 있을 수 있는 최대 시간이다. */
    public static final Duration LIST_REDIS_TTL = Duration.ofMinutes(30);

    /** 상세 캐시의 Redis TTL. 상세에는 L1이 없어 이 값 하나뿐이다. */
    public static final Duration DETAIL_REDIS_TTL = Duration.ofMinutes(30);

    /**
     * - 엔트리가 하나뿐인 캐시의 키
     *   - 목록 7종은 "그 캐시 = 그 값"이라 키로 나눌 것이 없음
     *   - 전시 상세만 예외로 전시 id를 키로 사용
     */
    public static final String ENTRY_KEY = "ALL";

    /**
     * - 목록 캐시 7종(배너 포함)
     *   - 전시가 바뀌면(관리자 수정·수집 등록) 이 목록을 통째로 지움
     *   - 어느 목록에 그 전시가 들어 있는지 따지지 않는 이유: 키가 7개뿐이라 전부 지우는 쪽이 싸고 틀릴 일이 없음
     */
    public static final List<MyCache> LISTS = List.of(
            HomeBanners.INSTANCE, HomeEndingSoon.INSTANCE, HomeFree.INSTANCE, HomeNewThisMonth.INSTANCE,
            ExploreLatestP1.INSTANCE, ExploreEndingP1.INSTANCE, ExplorePopularP1.INSTANCE);

    /** 조립(CacheConfig)이 순회할 전체 선언 목록. */
    public static final List<MyCache> ALL = List.of(
            HomeBanners.INSTANCE, HomeEndingSoon.INSTANCE, HomeFree.INSTANCE, HomeNewThisMonth.INSTANCE,
            ExploreLatestP1.INSTANCE, ExploreEndingP1.INSTANCE, ExplorePopularP1.INSTANCE,
            ExhibitionDetail.INSTANCE);

    private ExhibitionCache() {
    }

    /*
     * 홈 화면
     */

    /**
     * - 홈 배너는 5분 랭킹 재계산 직후에 지움(덮어쓰지 않음)
     *   - 다음 조회가 새 순위로 다시 적재함
     */
    public static final class HomeBanners extends MyCache.TwoTierCache {
        public static final HomeBanners INSTANCE = new HomeBanners();

        private HomeBanners() {
            super("홈 배너 목록", LIST_LOCAL_TTL, LIST_REDIS_TTL, ExhibitionResult.Banners.class);
        }
    }

    public static final class HomeEndingSoon extends MyCache.TwoTierCache {
        public static final HomeEndingSoon INSTANCE = new HomeEndingSoon();

        private HomeEndingSoon() {
            super("곧 끝나는 전시", LIST_LOCAL_TTL, LIST_REDIS_TTL, ExhibitionResult.ListPage.class);
        }
    }

    public static final class HomeNewThisMonth extends MyCache.TwoTierCache {
        public static final HomeNewThisMonth INSTANCE = new HomeNewThisMonth();

        private HomeNewThisMonth() {
            super("이번달 신규 전시", LIST_LOCAL_TTL, LIST_REDIS_TTL, ExhibitionResult.ListPage.class);
        }
    }

    public static final class HomeFree extends MyCache.TwoTierCache {
        public static final HomeFree INSTANCE = new HomeFree();

        private HomeFree() {
            super("무료 전시", LIST_LOCAL_TTL, LIST_REDIS_TTL, ExhibitionResult.ListPage.class);
        }
    }

    /*
     * 전시 탐색 화면
     */

    public static final class ExploreLatestP1 extends MyCache.TwoTierCache {
        public static final ExploreLatestP1 INSTANCE = new ExploreLatestP1();

        private ExploreLatestP1() {
            super("최신순 1페이지", LIST_LOCAL_TTL, LIST_REDIS_TTL, ExhibitionResult.ListPage.class);
        }
    }

    public static final class ExploreEndingP1 extends MyCache.TwoTierCache {
        public static final ExploreEndingP1 INSTANCE = new ExploreEndingP1();

        private ExploreEndingP1() {
            super("종료순 1페이지", LIST_LOCAL_TTL, LIST_REDIS_TTL, ExhibitionResult.ListPage.class);
        }
    }

    public static final class ExplorePopularP1 extends MyCache.TwoTierCache {
        public static final ExplorePopularP1 INSTANCE = new ExplorePopularP1();

        private ExplorePopularP1() {
            super("인기순 1페이지", LIST_LOCAL_TTL, LIST_REDIS_TTL, ExhibitionResult.ListPage.class);
        }
    }

    /**
     * - 전시 상세 조회 화면. 엔트리 키는 전시 id
     * - 로컬 복사본 없이 Redis만 씀
     *   - 영업시간·가격은 DB와 어긋나면 안 되는 값이라, 삭제 한 번으로 두 서버가 함께 새 값을 보게 함
     *   - L1을 두면 삭제를 처리하지 않은 서버가 L1 TTL 동안 옛 값을 서빙함
     */
    public static final class ExhibitionDetail extends MyCache.RedisCache {
        public static final ExhibitionDetail INSTANCE = new ExhibitionDetail();

        private ExhibitionDetail() {
            super("전시 상세", DETAIL_REDIS_TTL, ExhibitionResult.Detail.class);
        }
    }

}
