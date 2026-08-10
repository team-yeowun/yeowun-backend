package modi.backend.application.exhibition.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;

/**
 * - 캐시 페이지에 개인화를 덮어쓰는 창구({@code personalize})를 고정
 *   - 페이지의 나머지 필드(nextCursor·hasNext·totalCount)가 보존되는지가 핵심
 *   - 여기서 흘리면 캐시를 탄 목록만 페이지네이션이 끊긴다
 *
 * - 조립부는 모킹
 *   - 관심 여부 덮어쓰기 자체는 {@code ExhibitionListAssemblerBookmarkTest}가 검증
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionListServicePersonalizeTest {

	@Mock
	private ExhibitionQueryRepository exhibitionQueryRepository;
	@Mock
	private ExhibitionSearchQueryFactory queryFactory;
	@Mock
	private ExhibitionListAssembler listAssembler;

	@InjectMocks
	private ExhibitionListService service;

	private static ExhibitionResult.ListItem 항목(long id, boolean bookmarked) {
		return new ExhibitionResult.ListItem(id, "CATALOG", "전시" + id, "poster", LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 4, 30), "전시장", "SEOUL", "ART", null, 12, true, bookmarked);
	}

	private static ExhibitionResult.ListPage 페이지(List<ExhibitionResult.ListItem> content) {
		return new ExhibitionResult.ListPage(content, "cursor-abc", true, 126L);
	}

	@Test
	@DisplayName("관심 여부만 갈리고 커서·hasNext·총 건수는 그대로다")
	void personalize_페이지필드_보존() {
		given(listAssembler.withBookmarks(any(), anyLong())).willReturn(List.of(항목(1L, true)));

		ExhibitionResult.ListPage result = service.personalize(페이지(List.of(항목(1L, false))), 100L);

		assertThat(result.content()).singleElement()
				.extracting(ExhibitionResult.ListItem::bookmarked).isEqualTo(true);
		assertThat(result.nextCursor()).isEqualTo("cursor-abc");
		assertThat(result.hasNext()).isTrue();
		assertThat(result.totalCount()).isEqualTo(126L);
	}

	@Test
	@DisplayName("비로그인이면 조립부를 부르지 않고 원본 페이지를 그대로 돌려준다")
	void personalize_비로그인_원본그대로() {
		ExhibitionResult.ListPage page = 페이지(List.of(항목(1L, false)));

		assertThat(service.personalize(page, null)).isSameAs(page);
		verifyNoInteractions(listAssembler);
	}

	@Test
	@DisplayName("내용이 비면 조립부를 부르지 않는다")
	void personalize_빈페이지_조회없음() {
		ExhibitionResult.ListPage page = 페이지(List.of());

		assertThat(service.personalize(page, 100L)).isSameAs(page);
		verify(listAssembler, never()).withBookmarks(any(), anyLong());
	}

	@Test
	@DisplayName("개인화는 DB 목록 조회를 다시 하지 않는다 — 캐시가 아낀 조회를 도로 까먹지 않는다")
	void personalize_목록재조회_없음() {
		given(listAssembler.withBookmarks(any(), anyLong())).willReturn(List.of(항목(1L, true)));

		service.personalize(페이지(List.of(항목(1L, false))), 100L);

		verifyNoInteractions(exhibitionQueryRepository, queryFactory);
	}
}
