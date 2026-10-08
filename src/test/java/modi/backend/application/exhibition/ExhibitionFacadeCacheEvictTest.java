package modi.backend.application.exhibition;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.application.exhibition.custom.ExhibitionCustomService;
import modi.backend.application.exhibition.detail.ExhibitionDetailService;
import modi.backend.application.exhibition.list.ExhibitionListService;
import modi.backend.application.exhibition.ranking.ExhibitionRankingService;
import modi.backend.application.exhibition.view.ExhibitionViewCountService;
import modi.backend.support.cache.CacheManager;
import modi.backend.support.cache.MyCache;

/**
 * - 파사드의 쓰기 쪽 캐시 처리가 Cache-Aside(새 값을 넣지 않고 지우기만)인지 고정
 *   - 랭킹 재계산 뒤 배너는 지우기만 함 — 다음 조회가 새 순위로 적재
 *   - 관리자 수동 비우기는 목록 7종을 지움
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionFacadeCacheEvictTest {

	@Mock
	private ExhibitionListService exhibitionListService;
	@Mock
	private ExhibitionRankingService exhibitionRankingService;
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

	@Test
	@DisplayName("랭킹 재계산이 끝난 뒤 홈 배너 캐시를 지운다(덮어쓰지 않음)")
	void rebuildRankings_재계산뒤_배너삭제() {
		given(exhibitionRankingService.rebuildAll()).willReturn(new ExhibitionResult.RankingRebuild(3, Map.of()));

		facade.rebuildRankings();

		InOrder order = inOrder(exhibitionRankingService, cacheManager);
		order.verify(exhibitionRankingService).rebuildAll();
		order.verify(cacheManager).evict(ExhibitionCache.HomeBanners.INSTANCE, ExhibitionCache.ENTRY_KEY);
		verify(cacheManager, never()).put(any(), anyString(), any());
	}

	@Test
	@DisplayName("랭킹 재계산이 실패하면 배너를 지우지 않는다 — 반쯤 만든 순위로 다시 적재되지 않게")
	void rebuildRankings_실패_배너유지() {
		willThrow(new IllegalStateException("redis down")).given(exhibitionRankingService).rebuildAll();

		Assertions.assertThatThrownBy(() -> facade.rebuildRankings()).isInstanceOf(IllegalStateException.class);

		verify(cacheManager, never()).evict(any(), anyString());
	}

	@Test
	@DisplayName("수동 비우기는 목록 7종을 지운다")
	void evictListCaches_목록7종() {
		facade.evictListCaches();

		for (MyCache list : ExhibitionCache.LISTS) {
			verify(cacheManager).evict(list, ExhibitionCache.ENTRY_KEY);
		}
		verify(cacheManager, never()).evict(ExhibitionCache.ExhibitionDetail.INSTANCE, ExhibitionCache.ENTRY_KEY);
	}
}
