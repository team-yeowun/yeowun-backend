package modi.backend.application.exhibition.cache;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.application.exhibition.ExhibitionCriteria;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.application.exhibition.list.ExhibitionBannerService;
import modi.backend.application.exhibition.list.ExhibitionListService;
import modi.backend.support.cache.CacheManager;
import modi.backend.support.cache.MyCache;

/**
 * - 목록 캐시 재적재
 *   - {@code refresh}가 L2 갱신과 전 서버 L1 무효화 방송을 함께 함
 *   - evict된 L1은 다음 조회가 L2에서 되채움
 *   - 그래서 사용자가 Miss를 체감하는 구간이 없음
 *
 * - 파사드가 아니라 서비스를 부름
 *   - 파사드를 부르면 캐시에 있던 옛 값이 그대로 돌아와 워밍이 아무 일도 하지 않음
 *   - 새 값을 만들어야 하니 캐시를 건너뛰고 서비스를 직접 부름
 *   - 워머는 서비스가 아니라 배치가 들어오는 진입점이라 "서비스끼리 부르지 않는다" 규칙에 걸리지 않음
 *
 * - 하나가 실패해도 나머지는 계속함
 *   - 워밍 실패는 사고가 아니라 다음 주기까지 옛 값이 서빙되는 것뿐
 *   - 두 번 연속 실패해야 L2가 만료되고 Lazy Loading이 받음
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExhibitionCacheWarmer {

	private final ExhibitionBannerService exhibitionBannerService;
	private final ExhibitionListService exhibitionListService;
	private final CacheManager cacheManager;

	/** 목록 7종을 새 값으로 반영한다. */
	public void warmLists() {
		warm(ExhibitionCache.HomeBanners.INSTANCE,
				() -> new ExhibitionResult.Banners(exhibitionBannerService.banners()));
		warm(ExhibitionCache.HomeEndingSoon.INSTANCE,
				() -> exhibitionListService.search(query("ending-soon", "latest")));
		warm(ExhibitionCache.HomeFree.INSTANCE,
				() -> exhibitionListService.search(query("free", "latest")));
		warm(ExhibitionCache.HomeNewThisMonth.INSTANCE,
				() -> exhibitionListService.search(query("opening-this-month", "latest")));
		warm(ExhibitionCache.ExploreLatestP1.INSTANCE, () -> exhibitionListService.search(query(null, "latest")));
		warm(ExhibitionCache.ExploreEndingP1.INSTANCE, () -> exhibitionListService.search(query(null, "ending")));
		warm(ExhibitionCache.ExplorePopularP1.INSTANCE, () -> exhibitionListService.search(query(null, "popular")));
	}

	/**
	 * - 캐시에 담기는 것과 같은 익명 입력
	 *   - 요청자가 섞이면 그 사람의 관심 상태가 전 사용자용 캐시에 굳음
	 *   - 필터·커서도 없어야 조회 경로가 만드는 값과 같은 것이 담김
	 */
	private static ExhibitionCriteria.Search query(String section, String sort) {
		return new ExhibitionCriteria.Search(null, section, null, null, null, null, sort, null, null, null, null,
				null);
	}

	private void warm(MyCache cache, Supplier<Object> loader) {
		try {
			cacheManager.refresh(cache, ExhibitionCache.ENTRY_KEY, loader.get());
		} catch (Exception e) {
			log.warn("워밍 실패, Lazy Loading이 받친다: {}", cache.getName(), e);
		}
	}
}
