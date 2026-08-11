package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.application.exhibition.custom.ExhibitionCustomService;
import modi.backend.application.exhibition.detail.ExhibitionDetailService;
import modi.backend.application.exhibition.list.ExhibitionBannerService;
import modi.backend.application.exhibition.list.ExhibitionListService;
import modi.backend.application.exhibition.view.ExhibitionViewCountService;
import modi.backend.support.cache.CacheManager;

/**
 * - 목록 캐시 배선(STEP 5-5)이 개인화를 캐시에 굳히지 않는지 고정
 *   - 캐시에 담기는 값은 반드시 익명 결과여야 함
 *   - 로더에 요청자가 실린 채로 들어가면, 먼저 도착한 로그인 사용자의 관심 상태가 캐시에 굳음
 *   - 그 뒤 모든 사용자가 남의 하트를 보게 됨 — 성능이 아니라 정확성·프라이버시 문제
 *
 * - 캐시를 타지 않는 경로는 요청자를 그대로 유지해야 함
 *   - 필터가 붙은 목록에서 요청자까지 지우면 로그인 사용자의 관심 표시가 통째로 사라짐
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionFacadeSearchCacheTest {

	@Mock
	private ExhibitionListService exhibitionListService;
	@Mock
	private ExhibitionBannerService exhibitionBannerService;
	@Mock
	private ExhibitionDetailService exhibitionDetailService;
	@Mock
	private ExhibitionCustomService exhibitionCustomService;
	@Mock
	private ExhibitionViewCountService exhibitionViewCountService;
	@Mock
	private CacheManager cacheManager;

	@InjectMocks
	private ExhibitionFacade facade;

	private static final ExhibitionResult.ListPage 익명페이지 = new ExhibitionResult.ListPage(
			List.of(new ExhibitionResult.ListItem(1L, "CATALOG", "전시", "poster", LocalDate.of(2026, 3, 1),
					LocalDate.of(2026, 4, 30), "전시장", "SEOUL", "ART", null, 12, true, false)),
			null, false, 1L);

	/** 필터 없는 첫 화면(캐시 대상). */
	private static ExhibitionCriteria.Search 첫화면(Long requesterId) {
		return new ExhibitionCriteria.Search(null, null, null, null, null, null, "latest", null, null, null,
				null, requesterId);
	}

	/** 지역 필터가 붙은 요청(캐시 비대상). */
	private static ExhibitionCriteria.Search 필터목록(Long requesterId) {
		return new ExhibitionCriteria.Search(null, null, null, "SEOUL", null, null, "latest", null, null, null,
				null, requesterId);
	}

	/** getOrPut을 캐시 미스처럼 동작시켜 로더를 실제로 실행한다. */
	private void 캐시미스() {
		given(cacheManager.getOrPut(any(), anyString(), any(), any()))
				.willAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
	}

	@Test
	@DisplayName("캐시 로더에는 요청자를 지운 익명 입력이 들어간다 — 남의 관심 상태가 캐시에 굳지 않는다")
	void search_로더입력_익명() {
		캐시미스();
		given(exhibitionListService.search(any())).willReturn(익명페이지);
		given(exhibitionListService.personalize(any(), anyLong())).willReturn(익명페이지);

		facade.search(첫화면(100L));

		ArgumentCaptor<ExhibitionCriteria.Search> 로더입력 = ArgumentCaptor.forClass(ExhibitionCriteria.Search.class);
		verify(exhibitionListService).search(로더입력.capture());
		assertThat(로더입력.getValue().requesterId()).isNull();
	}

	@Test
	@DisplayName("익명화는 요청자만 지운다 — 섹션이 함께 지워지면 홈 섹션 키에 전체 목록이 담긴다")
	void search_익명화_요청자만제거() {
		캐시미스();
		given(exhibitionListService.search(any())).willReturn(익명페이지);
		given(exhibitionListService.personalize(any(), anyLong())).willReturn(익명페이지);

		ExhibitionCriteria.Search 무료섹션 = new ExhibitionCriteria.Search(null, "free", null, null, null, null,
				"latest", null, null, null, null, 100L);
		facade.search(무료섹션);

		// 필드 하나만 흘리거나 뒤바꿔도 잡히도록 record를 통째로 비교한다.
		ArgumentCaptor<ExhibitionCriteria.Search> 로더입력 = ArgumentCaptor.forClass(ExhibitionCriteria.Search.class);
		verify(exhibitionListService).search(로더입력.capture());
		assertThat(로더입력.getValue()).isEqualTo(new ExhibitionCriteria.Search(null, "free", null, null, null,
				null, "latest", null, null, null, null, null));
	}

	@Test
	@DisplayName("캐시에서 꺼낸 뒤에는 요청자 기준으로 개인화한다")
	void search_캐시히트_개인화() {
		given(cacheManager.getOrPut(any(), anyString(), any(), any())).willReturn(익명페이지);
		given(exhibitionListService.personalize(익명페이지, 100L)).willReturn(익명페이지);

		facade.search(첫화면(100L));

		verify(exhibitionListService).personalize(익명페이지, 100L);
	}

	@Test
	@DisplayName("캐시 히트면 목록 조회를 하지 않는다")
	void search_캐시히트_DB조회없음() {
		given(cacheManager.getOrPut(any(), anyString(), any(), any())).willReturn(익명페이지);
		given(exhibitionListService.personalize(any(), anyLong())).willReturn(익명페이지);

		facade.search(첫화면(100L));

		verify(exhibitionListService, org.mockito.Mockito.never()).search(any());
	}

	@Test
	@DisplayName("필터가 붙으면 캐시를 거치지 않고, 요청자도 지우지 않는다")
	void search_비대상_요청자유지() {
		ExhibitionCriteria.Search criteria = 필터목록(100L);
		given(exhibitionListService.search(criteria)).willReturn(익명페이지);

		facade.search(criteria);

		verifyNoInteractions(cacheManager);
		verify(exhibitionListService).search(criteria);
		verify(exhibitionListService, org.mockito.Mockito.never()).personalize(any(), anyLong());
	}

	@Test
	@DisplayName("정렬 축마다 다른 캐시 선언을 사용한다 — 인기순 결과가 최신순 자리에 들어가지 않는다")
	void search_정렬축별_캐시선언() {
		given(cacheManager.getOrPut(any(), anyString(), any(), any())).willReturn(익명페이지);
		given(exhibitionListService.personalize(any(), anyLong())).willReturn(익명페이지);

		facade.search(new ExhibitionCriteria.Search(null, null, null, null, null, null, "popular", null, null,
				null, null, 100L));

		verify(cacheManager).getOrPut(org.mockito.ArgumentMatchers.eq(ExhibitionCache.ExplorePopularP1.INSTANCE),
				org.mockito.ArgumentMatchers.eq(ExhibitionCache.ENTRY_KEY), any(), any());
	}
}
