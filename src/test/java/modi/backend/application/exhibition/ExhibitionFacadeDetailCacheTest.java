package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.application.exhibition.cache.ExhibitionCacheWarmer;
import modi.backend.application.exhibition.custom.ExhibitionCustomService;
import modi.backend.application.exhibition.detail.ExhibitionDetailService;
import modi.backend.application.exhibition.list.ExhibitionBannerService;
import modi.backend.application.exhibition.list.ExhibitionListService;
import modi.backend.application.exhibition.view.ExhibitionViewCountService;
import modi.backend.support.cache.CacheManager;
import modi.backend.support.error.CoreException;
import modi.backend.support.error.ErrorType;

/**
 * - 상세 캐시가 권한을 새지 않게 하는지 고정
 *   - 캐시에 CUSTOM이 한 번이라도 들어가면 그 뒤 모든 요청이 캐시 히트로 권한 판정을 건너뜀
 *   - 즉 타인의 개인 전시가 열림 — 성능이 아니라 보안 문제
 *
 * - 캐시 히트 경로가 조립을 건너뛰는지도 함께 봄
 *   - 익명 CATALOG 상세는 캐시 히트 시 DB를 건드리지 않는 것이 이 STEP의 목적
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionFacadeDetailCacheTest {

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
	private ExhibitionCacheWarmer exhibitionCacheWarmer;
	@Mock
	private CacheManager cacheManager;

	@InjectMocks
	private ExhibitionFacade facade;

	private static ExhibitionResult.Detail 상세(long id) {
		return new ExhibitionResult.Detail(id, "CATALOG", "전시", "poster", LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 4, 30), "전시장", "SEOUL", "ART", "전시", "설명", "10:00-18:00", "무료",
				List.of(), List.of(), "기관", "url", 127.0, 37.5, "주소", "img", "02-000-0000", 10L, "종로구",
				"placeUrl", null, true, false, false);
	}

	@Test
	@DisplayName("CATALOG 상세는 캐시에 담긴다")
	void getDetail_CATALOG_캐시에담김() {
		given(cacheManager.get(any(), anyString(), any())).willReturn(null);
		given(exhibitionDetailService.assembleShared(1L, null))
				.willReturn(new ExhibitionResult.SharedDetail(상세(1L), true));
		given(exhibitionDetailService.personalize(any(), isNull())).willReturn(상세(1L));

		facade.getDetail(new ExhibitionCriteria.Detail(1L, null));

		verify(cacheManager).put(eq(ExhibitionCache.ExhibitionDetail.INSTANCE), eq("1"), any());
	}

	@Test
	@DisplayName("CUSTOM 상세는 캐시에 담기지 않는다 — 담기면 캐시 히트가 권한 판정을 건너뛴다")
	void getDetail_CUSTOM_캐시에안담김() {
		given(cacheManager.get(any(), anyString(), any())).willReturn(null);
		given(exhibitionDetailService.assembleShared(4L, 10L))
				.willReturn(new ExhibitionResult.SharedDetail(상세(4L), false));
		given(exhibitionDetailService.personalize(any(), eq(10L))).willReturn(상세(4L));

		facade.getDetail(new ExhibitionCriteria.Detail(4L, 10L));

		verify(cacheManager, never()).put(any(), anyString(), any());
	}

	@Test
	@DisplayName("캐시 히트면 조립을 건너뛰고 개인화만 한다 — 익명 CATALOG는 DB 0회")
	void getDetail_캐시히트_조립건너뜀() {
		given(cacheManager.get(any(), anyString(), any())).willReturn(상세(1L));
		given(exhibitionDetailService.personalize(any(), isNull())).willReturn(상세(1L));

		facade.getDetail(new ExhibitionCriteria.Detail(1L, null));

		verify(exhibitionDetailService, never()).assembleShared(any(), any());
		verify(cacheManager, never()).put(any(), anyString(), any());
	}

	@Test
	@DisplayName("캐시 히트여도 개인화는 매번 돈다 — 조회수와 관심 여부가 요청자마다 다르다")
	void getDetail_캐시히트_개인화는수행() {
		ExhibitionResult.Detail cached = 상세(1L);
		given(cacheManager.get(any(), anyString(), any())).willReturn(cached);
		given(exhibitionDetailService.personalize(cached, 42L)).willReturn(상세(1L));

		facade.getDetail(new ExhibitionCriteria.Detail(1L, 42L));

		verify(exhibitionDetailService).personalize(cached, 42L);
	}

	@Test
	@DisplayName("타인의 CUSTOM은 403이 그대로 전파되고 캐시에 아무것도 남지 않는다")
	void getDetail_타인CUSTOM_403_캐시무변화() {
		given(cacheManager.get(any(), anyString(), any())).willReturn(null);
		willThrow(new CoreException(ErrorType.FORBIDDEN, "타인의 개인 전시 접근: 3"))
				.given(exhibitionDetailService).assembleShared(3L, 20L);

		assertThatThrownBy(() -> facade.getDetail(new ExhibitionCriteria.Detail(3L, 20L)))
				.isInstanceOf(CoreException.class)
				.satisfies(ex -> assertThat(((CoreException) ex).errorCode()).isEqualTo(ErrorType.FORBIDDEN));

		verify(cacheManager, never()).put(any(), anyString(), any());
	}

	@Test
	@DisplayName("캐시 키는 전시 id다 — 상세만은 엔트리가 여러 개다")
	void getDetail_캐시키_전시id() {
		given(cacheManager.get(any(), eq("77"), any())).willReturn(상세(77L));
		given(exhibitionDetailService.personalize(any(), isNull())).willReturn(상세(77L));

		facade.getDetail(new ExhibitionCriteria.Detail(77L, null));

		verify(cacheManager).get(eq(ExhibitionCache.ExhibitionDetail.INSTANCE), eq("77"), any());
	}
}
