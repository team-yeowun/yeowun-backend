package modi.backend.application.exhibition.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import modi.backend.application.exhibition.ExhibitionCriteria;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.application.exhibition.list.ExhibitionBannerService;
import modi.backend.application.exhibition.list.ExhibitionListService;
import modi.backend.support.cache.CacheManager;
import modi.backend.support.cache.MyCache;

/**
 * - 워머가 선언 7종을 모두 채우는지, 그리고 익명 입력으로 채우는지 고정
 *   - 하나라도 빠지면 그 캐시만 TTL 만료 후 Lazy Loading에 의존하게 됨
 *   - 요청자가 섞이면 그 사람의 관심 상태가 전 사용자용 캐시에 굳음
 *
 * - 리졸버가 조회 시 고르는 선언과 워머가 채우는 선언이 같아야 함
 *   - 어긋나면 채운 캐시를 아무도 찾지 않고, 찾는 캐시는 비어 있음
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionCacheWarmerTest {

	@Mock
	private ExhibitionBannerService exhibitionBannerService;
	@Mock
	private ExhibitionListService exhibitionListService;
	@Mock
	private CacheManager cacheManager;

	@InjectMocks
	private ExhibitionCacheWarmer warmer;

	private static ExhibitionResult.ListPage 페이지() {
		return new ExhibitionResult.ListPage(List.of(), null, false, 0L);
	}

	@Test
	@DisplayName("목록 7종을 모두 재적재한다 — 리졸버가 고르는 선언과 정확히 같은 집합이다")
	void warmLists_선언7종_전부재적재() {
		given(exhibitionListService.search(any())).willReturn(페이지());
		given(exhibitionBannerService.banners()).willReturn(List.of());

		warmer.warmLists();

		ArgumentCaptor<MyCache> 채운캐시 = ArgumentCaptor.forClass(MyCache.class);
		verify(cacheManager, times(7)).refresh(채운캐시.capture(), anyString(), any());
		assertThat(채운캐시.getAllValues()).containsExactlyInAnyOrder(
				ExhibitionCache.HomeBanners.INSTANCE, ExhibitionCache.HomeEndingSoon.INSTANCE,
				ExhibitionCache.HomeFree.INSTANCE, ExhibitionCache.HomeNewThisMonth.INSTANCE,
				ExhibitionCache.ExploreLatestP1.INSTANCE, ExhibitionCache.ExploreEndingP1.INSTANCE,
				ExhibitionCache.ExplorePopularP1.INSTANCE);
	}

	@Test
	@DisplayName("워밍 입력에는 요청자·필터·커서가 없다 — 남의 관심 상태가 굳지 않게")
	void warmLists_익명입력() {
		given(exhibitionListService.search(any())).willReturn(페이지());
		given(exhibitionBannerService.banners()).willReturn(List.of());

		warmer.warmLists();

		ArgumentCaptor<ExhibitionCriteria.Search> 입력 = ArgumentCaptor.forClass(ExhibitionCriteria.Search.class);
		verify(exhibitionListService, times(6)).search(입력.capture());
		assertThat(입력.getAllValues()).allSatisfy(criteria -> {
			assertThat(criteria.requesterId()).isNull();
			assertThat(criteria.cursor()).isNull();
			assertThat(criteria.region()).isNull();
			assertThat(criteria.keyword()).isNull();
			assertThat(criteria.size()).isNull();
		});
	}

	@Test
	@DisplayName("워밍 입력은 전부 조회 캐시 대상이다 — 리졸버가 같은 선언을 고른다")
	void warmLists_입력이_리졸버판정과_일치() {
		given(exhibitionListService.search(any())).willReturn(페이지());
		given(exhibitionBannerService.banners()).willReturn(List.of());

		warmer.warmLists();

		ArgumentCaptor<ExhibitionCriteria.Search> 입력 = ArgumentCaptor.forClass(ExhibitionCriteria.Search.class);
		verify(exhibitionListService, times(6)).search(입력.capture());
		// 워머가 만든 입력을 조회 경로의 리졸버에 그대로 넣어, 워밍한 캐시를 실제로 찾아내는지 본다.
		assertThat(입력.getAllValues()).allSatisfy(criteria ->
				assertThat(ExhibitionListCacheResolver.resolve(criteria)).isPresent());
	}

	@Test
	@DisplayName("하나가 실패해도 나머지는 계속 채운다 — 워밍 실패는 사고가 아니다")
	void warmLists_일부실패_나머지진행() {
		given(exhibitionBannerService.banners()).willThrow(new IllegalStateException("배너 조회 실패"));
		given(exhibitionListService.search(any())).willReturn(페이지());

		assertThatCode(() -> warmer.warmLists()).doesNotThrowAnyException();

		// 배너 하나만 빠지고 목록 6종은 그대로 채워진다.
		verify(cacheManager, times(6)).refresh(any(), anyString(), any());
	}

	@Test
	@DisplayName("refresh를 쓴다 — L2 갱신과 전 서버 L1 무효화 방송이 함께 나가야 한다")
	void warmLists_refresh사용() {
		given(exhibitionListService.search(any())).willReturn(페이지());
		given(exhibitionBannerService.banners()).willReturn(List.of());

		warmer.warmLists();

		// put이면 자기 L1만 갱신돼 다른 서버는 옛 값을 계속 서빙한다.
		verify(cacheManager, times(7)).refresh(any(), eq(ExhibitionCache.ENTRY_KEY), any());
		verify(cacheManager, org.mockito.Mockito.never()).put(any(), anyString(), any());
	}

	@Test
	@DisplayName("워머는 파사드가 아니라 서비스를 부른다 — 파사드면 캐시의 옛 값이 그대로 돌아온다")
	void warmLists_서비스직접호출() {
		given(exhibitionListService.search(any())).willReturn(페이지());
		given(exhibitionBannerService.banners()).willReturn(List.of());

		warmer.warmLists();

		// 캐시를 거치지 않고 새 값을 만들어야 워밍이 의미가 있다.
		verify(exhibitionListService, times(6)).search(any());
		verify(exhibitionBannerService).banners();
	}
}
