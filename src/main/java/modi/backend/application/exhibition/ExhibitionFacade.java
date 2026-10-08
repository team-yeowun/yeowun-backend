package modi.backend.application.exhibition;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.application.exhibition.cache.ExhibitionListCacheResolver;
import modi.backend.application.exhibition.custom.ExhibitionCustomService;
import modi.backend.application.exhibition.detail.ExhibitionDetailService;
import modi.backend.application.exhibition.ranking.ExhibitionRankingService;
import modi.backend.application.exhibition.list.ExhibitionListService;
import modi.backend.application.exhibition.view.ExhibitionViewCountService;
import modi.backend.support.cache.CacheManager;
import modi.backend.support.cache.MyCache;
import org.springframework.stereotype.Service;

/**
 * 전시 사용자 유스케이스의 <b>단일 진입점</b>(03_전시.md). interfaces는 이 파사드만 호출하고, 실제 조율은 책임별 서비스가 맡는다 —
 * 목록/탐색({@link ExhibitionListService}) · 랭킹·배너({@link ExhibitionRankingService}) · 상세({@link ExhibitionDetailService}) · 개인
 * 전시 등록·삭제({@link ExhibitionCustomService}).
 *
 * <p><b>왜 갈랐나</b>: 한 클래스가 리포지토리 8개를 들고 있었고, 그중 작가·전시관·장르 분류기(AI)는 <b>등록에서만</b>
 * 쓰였다. 목록을 그리는 경로가 Gemini 클라이언트를 함께 들고 다닐 이유가 없다. 협력자가 갈리는 선을 그대로 잘랐다.
 *
 * <p>수집·보강 파이프라인은 ingestion 슬라이스({@code ExhibitionIngestionOrchestrator})가 따로 담당한다.
 * 장소는 N:1, 상세는 1:1, 작가는 N:M 조인이라 응답 조립 시 애그리거트 루트 포트에서 읽어 모은다(API 계약 불변).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionFacade {

    private final ExhibitionListService exhibitionListService;
    private final ExhibitionRankingService exhibitionRankingService;
    private final ExhibitionDetailService exhibitionDetailService;
    private final ExhibitionCustomService exhibitionCustomService;
    private final ExhibitionViewCountService exhibitionViewCountService;
    private final CacheManager cacheManager;

    /**
     * 지역 필터 그룹 목록(디자인 병합 칩).
     */
    public List<ExhibitionResult.RegionGroup> getRegionGroups() {
        return exhibitionListService.getRegionGroups();
    }

    /**
     * - 목록/탐색(5.2)
     *   - 필터 미지정 시 오늘 진행 중인 전시를 기본 노출
     *   - 총 건수는 {@link #count}가 따로 줌(그쪽은 캐시하지 않아 항상 실시간)
     *
     * - 필터 없는 첫 페이지만 캐시를 탐
     *   - 대상 판정은 {@link ExhibitionListCacheResolver}
     *   - 대상이 아니면 원본 입력 그대로 서비스로 내려감(요청자도 유지 — 관심 표시가 사라지지 않게)
     *
     * - 캐시에는 익명 결과를 담고, 요청자의 관심 여부는 캐시 밖에서 덮어씀
     *   - 로더 입력에서 요청자를 지우는 것이 {@link #anonymous}
     *   - 꺼낸 뒤 덮어쓰는 것이 {@code personalize}
     *   - 캐시에 개인화가 섞이면 먼저 도착한 사용자의 관심 상태가 전 사용자에게 나감
     *
     * - 트랜잭션을 걸지 않음
     *   - 캐시를 파사드에 올린 이유가 적중한 요청이 트랜잭션 프록시를 지나지 않게 하는 것
     *   - 여기 걸면 그 이득이 통째로 사라짐
     */
    public ExhibitionResult.ListPage search(ExhibitionCriteria.Search criteria) {
        Optional<MyCache> cache = ExhibitionListCacheResolver.resolve(criteria);
        if (cache.isEmpty()) {
            return exhibitionListService.search(criteria);
        }
        ExhibitionResult.ListPage shared = cacheManager.getOrPut(
                cache.get(), ExhibitionCache.ENTRY_KEY, ExhibitionResult.ListPage.class,
                () -> exhibitionListService.search(anonymous(criteria)));

        return exhibitionListService.personalize(shared, criteria.requesterId());
    }

    /**
     * 같은 필터의 총 건수. 목록과 같은 입력·같은 조건 조립 경로를 공유한다(필터가 어긋날 여지를 없앤다).
     */
    public ExhibitionResult.Count count(ExhibitionCriteria.Search criteria) {
        return exhibitionListService.count(criteria);
    }

    /**
     * - 홈 배너(E-10). 오늘 진행 중인 전시 중 최근 3일 조회수 상위 최대 3개(동점은 개막일 최신순)
     *   - 응답 형식은 예전 그대로, 고르는 기준만 누적 조회수에서 랭킹 순위판으로 바뀜
     *   - 캐시는 랭킹 재계산 직후 {@link #rebuildRankings}가 지우고, 다음 조회가 새 순위로 적재함
     */
    public List<ExhibitionResult.Banner> banners() {
        return cacheManager.getOrPut(
                        ExhibitionCache.HomeBanners.INSTANCE, ExhibitionCache.ENTRY_KEY,
                        ExhibitionResult.Banners.class,
                        () -> new ExhibitionResult.Banners(exhibitionRankingService.banners()))
                .items();
    }

    /**
     * - 상세(5.3). 없으면 404, 타인의 CUSTOM이면 403
     *
     * - 캐시에는 CATALOG의 공용 조립부만 담음
     *   - 캐시 히트가 곧 공개 전시라는 증명이라 히트 경로에서 권한을 다시 보지 않아도 됨
     *   - CUSTOM은 캐시에 들어간 적이 없어 항상 미스로 떨어지고, 그때 loader가 권한을 판정
     *
     * - {@code getOrPut}이 아니라 {@code get} + 조건부 {@code put}을 씀
     *   - {@code getOrPut}은 loader가 만든 값을 무조건 넣어 CUSTOM까지 담김
     *   - 여기서만은 "넣을지 말지"를 호출부가 정해야 함
     *
     * - 상세 캐시는 Redis 하나뿐(로컬 복사본 없음) — 조회 순서는 Redis → DB
     *
     * - 조회수는 PR #155 이후 누산기로만 가므로 이 경로에 DB 쓰기가 없음
     *   - 히트 시 조립 쿼리(SELECT)는 나가지 않음
     *   - 다만 {@code personalize}가 읽기 트랜잭션이라, 복제본 라우팅(지연 커넥션)이 없는 구성에서는
     *     히트여도 커넥션을 잡고 트랜잭션 문장(SET·COMMIT)이 나감
     *
     * - 랭킹 기록도 Redis에만 감 (캐시 히트 여부와 무관하게 조회 1번 = 기록 1번)
     */
    public ExhibitionResult.Detail getDetail(ExhibitionCriteria.Detail criteria) {
        String key = String.valueOf(criteria.exhibitionId());
        ExhibitionResult.Detail shared = cacheManager.get(
                ExhibitionCache.ExhibitionDetail.INSTANCE, key, ExhibitionResult.Detail.class);

        if (shared == null) {
            ExhibitionResult.SharedDetail assembled = exhibitionDetailService.assembleShared(
                    criteria.exhibitionId(), criteria.requesterId());
            shared = assembled.detail();
            if (assembled.cacheable()) {
                cacheManager.put(ExhibitionCache.ExhibitionDetail.INSTANCE, key, shared);
            }
        }
        ExhibitionResult.Detail detail = exhibitionDetailService.personalize(shared, criteria.requesterId());
        exhibitionRankingService.recordView(detail);
        return detail;
    }

    /**
     * 스냅샷/조회용 — 조회수 증가·개인화 없이 DB에서만 읽어 반환한다(기록 생성 등 내부 사용).
     */
    public ExhibitionResult.Detail getForSnapshot(Long exhibitionId, Long requesterId) {
        return exhibitionDetailService.getForSnapshot(exhibitionId, requesterId);
    }

    /**
     * 개인 전시 등록(5.4).
     */
    public ExhibitionResult.Created registerCustom(ExhibitionCriteria.CustomCreate criteria) {
        return exhibitionCustomService.registerCustom(criteria);
    }

    /**
     * 개인 전시(CUSTOM) 동반 삭제 — 본인이 등록한 CUSTOM만 soft-delete. 멱등.
     */
    public void deleteCustomOwnedBy(Long exhibitionId, Long ownerId) {
        exhibitionCustomService.deleteCustomOwnedBy(exhibitionId, ownerId);
    }

    /**
     * 누산된 조회수를 정본에 반영한다(6시간 배치 진입점). 사용자 요청이 아니라 스케줄러가 부르지만, interfaces가 서비스를 직접 부르지 않도록 진입점은 파사드에 둔다.
     */
    public ExhibitionResult.ViewCountFlush flushViewCounts() {
        return exhibitionViewCountService.flush();
    }

    /**
     * - 랭킹 순위판을 원본에서 다시 만들고 홈 배너 캐시를 지움(5분 배치 진입점)
     *   - 두 앱이 각자 불러도 결과가 같아 락을 두지 않음
     *   - 지우기는 재계산이 끝난 뒤에만 함 (반쯤 만든 순위로 배너가 다시 적재되지 않게)
     *   - 새 값을 넣지 않고 지우기만 함 — 다음 조회가 새 순위로 적재함(Cache-Aside)
     *   - 두 앱이 각자 5분마다 돌아 각자의 L1 배너도 함께 지워짐
     */
    public ExhibitionResult.RankingRebuild rebuildRankings() {
        ExhibitionResult.RankingRebuild result = exhibitionRankingService.rebuildAll();
        cacheManager.evict(ExhibitionCache.HomeBanners.INSTANCE, ExhibitionCache.ENTRY_KEY);
        return result;
    }

    /**
     * - 목록 캐시 7종을 지움(관리자 수동 진입점)
     *   - Redis의 목록과 이 서버의 L1 목록을 지움. 다른 서버의 L1은 L1 TTL(10분)로 만료됨
     *   - DB를 직접 고친 뒤 목록을 바로 새로 보이게 할 때 씀
     *
     * - 사용자 요청이 아니지만 진입점은 파사드에 둠
     *   - interfaces가 application 내부 컴포넌트를 직접 부르지 않게 하기 위함
     */
    public void evictListCaches() {
        for (MyCache list : ExhibitionCache.LISTS) {
            cacheManager.evict(list, ExhibitionCache.ENTRY_KEY);
        }
    }

    /**
     * - 캐시에 담을 익명 조회 입력 — 요청자만 지움
     *   - 관심 여부가 캐시에 굳지 않게 하는 것이 목적
     *   - 나머지 필드는 조회 조건이라 그대로 넘겨야 함
     *
     * - 요청자 외의 필드를 흘리거나 뒤바꾸면 캐시 키와 내용이 어긋남
     *   - 예: {@code section}을 흘리면 "무료 전시" 키에 전체 목록이 담김
     *   - 전부 {@code String}이라 컴파일로는 안 잡힘 — {@code ExhibitionFacadeSearchCacheTest}가 record를 통째로 비교
     */
    private static ExhibitionCriteria.Search anonymous(ExhibitionCriteria.Search c) {
        return new ExhibitionCriteria.Search(c.keyword(), c.section(), c.period(), c.region(), c.category(),
                c.date(), c.sort(), c.lat(), c.lng(), c.cursor(), c.size(), null);
    }
}
