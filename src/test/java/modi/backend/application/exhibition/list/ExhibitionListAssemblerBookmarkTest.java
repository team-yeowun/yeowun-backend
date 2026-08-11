package modi.backend.application.exhibition.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;

/**
 * - 캐시 덮어쓰기({@code withBookmarks})가 개인화를 새지 않게 하는지 고정
 *   - 캐시에는 익명 결과가 담기고, 관심 여부만 캐시 밖에서 덮어씀
 *   - 이 덮어쓰기가 틀리면 남의 관심 상태가 전 사용자에게 나감 — 성능이 아니라 정확성 문제
 *
 * - 외부 I/O(리포지토리)만 모킹한 단위 테스트
 *   - 조립 규칙 검증에 스프링 컨텍스트가 필요 없음
 */
@ExtendWith(MockitoExtension.class)
class ExhibitionListAssemblerBookmarkTest {

	@Mock
	private ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Mock
	private ExhibitionBookmarkRepository exhibitionBookmarkRepository;

	@InjectMocks
	private ExhibitionListAssembler assembler;

	private static ExhibitionResult.ListItem 항목(long id, boolean bookmarked) {
		return new ExhibitionResult.ListItem(id, "CATALOG", "전시" + id, "poster", LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 4, 30), "전시장", "SEOUL", "ART", null, 12, true, bookmarked);
	}

	@Test
	@DisplayName("요청자가 관심 등록한 항목만 true가 된다")
	void withBookmarks_관심등록분만_표시() {
		given(exhibitionBookmarkRepository.findBookmarkedExhibitionIds(anyLong(), any())).willReturn(Set.of(2L));

		List<ExhibitionResult.ListItem> result = assembler.withBookmarks(
				List.of(항목(1L, false), 항목(2L, false), 항목(3L, false)), 100L);

		assertThat(result).extracting(ExhibitionResult.ListItem::exhibitionId,
				ExhibitionResult.ListItem::bookmarked)
				.containsExactly(tuple(1L, false), tuple(2L, true), tuple(3L, false));
	}

	@Test
	@DisplayName("캐시에 true가 굳어 있어도 관심 없는 요청자에겐 false로 내려간다")
	void withBookmarks_캐시에굳은값_요청자기준으로덮어씀() {
		given(exhibitionBookmarkRepository.findBookmarkedExhibitionIds(anyLong(), any())).willReturn(Set.of());

		List<ExhibitionResult.ListItem> result = assembler.withBookmarks(List.of(항목(1L, true)), 100L);

		assertThat(result).singleElement()
				.extracting(ExhibitionResult.ListItem::bookmarked).isEqualTo(false);
	}

	@Test
	@DisplayName("비로그인이면 조회 없이 원본을 그대로 돌려준다")
	void withBookmarks_비로그인_조회없음() {
		List<ExhibitionResult.ListItem> items = List.of(항목(1L, false));

		assertThat(assembler.withBookmarks(items, null)).isSameAs(items);
		verify(exhibitionBookmarkRepository, never()).findBookmarkedExhibitionIds(any(), any());
	}

	@Test
	@DisplayName("빈 목록이면 조회하지 않는다")
	void withBookmarks_빈목록_조회없음() {
		assertThat(assembler.withBookmarks(List.of(), 100L)).isEmpty();
		verify(exhibitionBookmarkRepository, never()).findBookmarkedExhibitionIds(any(), any());
	}

	@Test
	@DisplayName("항목이 몇 개든 관심 조회는 배치 한 번이다 — 캐시가 아낀 조회를 N+1로 도로 까먹지 않는다")
	void withBookmarks_배치1회() {
		given(exhibitionBookmarkRepository.findBookmarkedExhibitionIds(anyLong(), any())).willReturn(Set.of());

		assembler.withBookmarks(List.of(항목(1L, false), 항목(2L, false), 항목(3L, false), 항목(4L, false)), 100L);

		verify(exhibitionBookmarkRepository, times(1)).findBookmarkedExhibitionIds(anyLong(), any());
	}
}
