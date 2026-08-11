package modi.backend.application.exhibition.view;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import modi.backend.application.exhibition.cache.ExhibitionCache;
import modi.backend.domain.exhibition.catalog.ExhibitionViewCounter;
import modi.backend.support.cache.CacheManager;

/**
 * - 조회수 반영 후 상세 캐시를 지우는지 고정
 *   - 캐시된 상세는 담길 때의 정본 값을 들고 있음
 *   - 정본이 올라가고 누산분이 0이 되면 정본(옛값) + 0이라 표시가 뒤로 감
 *   - 사용자에겐 "보던 조회수가 줄어드는" 것으로 보이는 종류의 버그
 *
 * - 지우는 시점이 중요함
 *   - 반영이 실패하면 정본이 안 올라갔으므로 지울 이유도 없음
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionViewCountFlushEvictTest {

	@Mock
	private ExhibitionViewCounter viewCounter;
	@Mock
	private ExhibitionViewCountApplier viewCountApplier;
	@Mock
	private CacheManager cacheManager;

	@InjectMocks
	private ExhibitionViewCountService service;

	@Test
	@DisplayName("반영한 전시의 상세 캐시를 지운다 — 지우지 않으면 조회수 표시가 뒤로 간다")
	void flush_반영전시_상세캐시_evict() {
		given(viewCounter.drain()).willReturn(Map.of(1L, 3L, 2L, 5L));
		given(viewCountApplier.apply(anyMap())).willReturn(2);

		service.flush();

		verify(cacheManager).evict(ExhibitionCache.ExhibitionDetail.INSTANCE, "1");
		verify(cacheManager).evict(ExhibitionCache.ExhibitionDetail.INSTANCE, "2");
	}

	@Test
	@DisplayName("이번 창에 조회되지 않은 전시는 지우지 않는다 — 수거 결과의 키만 대상이다")
	void flush_조회안된전시는_그대로() {
		given(viewCounter.drain()).willReturn(Map.of(1L, 3L));
		given(viewCountApplier.apply(anyMap())).willReturn(1);

		service.flush();

		verify(cacheManager).evict(any(), eq("1"));
		verify(cacheManager, never()).evict(any(), eq("2"));
	}

	@Test
	@DisplayName("반영할 것이 없으면 캐시를 건드리지 않는다")
	void flush_수거분없음_캐시무접촉() {
		given(viewCounter.drain()).willReturn(Map.of());

		service.flush();

		verifyNoInteractions(cacheManager);
	}

	@Test
	@DisplayName("반영이 실패하면 캐시를 지우지 않는다 — 정본이 안 올라갔으니 지울 이유가 없다")
	void flush_반영실패_evict안함() {
		given(viewCounter.drain()).willReturn(Map.of(1L, 3L));
		willThrow(new IllegalStateException("반영 실패")).given(viewCountApplier).apply(anyMap());

		assertThatThrownBy(() -> service.flush()).isInstanceOf(IllegalStateException.class);

		verify(cacheManager, never()).evict(any(), anyString());
		verify(viewCounter).restoreDrained();
	}
}
