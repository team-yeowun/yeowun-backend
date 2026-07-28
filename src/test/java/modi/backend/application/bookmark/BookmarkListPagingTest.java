package modi.backend.application.bookmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionTestFactory;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.support.time.AppTime;

/**
 * 관심 전시 목록의 커서 페이지네이션을 검증한다(@SpringBootTest + Testcontainers-MySQL).
 *
 * <p><b>배경</b>: 예전에는 북마크 id 전량 → 전시 전량을 읽어 <b>앱 메모리에서</b> 정렬·슬라이스했다. 북마크가 많은
 * 사용자일수록 매 페이지 요청마다 전량을 읽는 구조였다. 등록순은 정렬 키가 북마크 테이블 자기 컬럼이라 거기서 자르고,
 * 종료임박순은 정렬 키가 전시 컬럼이라 정렬·경계·LIMIT을 DB에 맡긴다.
 *
 * <p>구현이 바뀌어도 <b>페이지를 이어 붙이면 전체가 순서대로 빠짐없이 나온다</b>는 계약은 그대로여야 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class BookmarkListPagingTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);
	private static final AtomicInteger USER = new AtomicInteger(9_000);

	@Autowired
	BookmarkFacade bookmarkFacade;
	@Autowired
	ExhibitionBookmarkRepository bookmarkRepository;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("등록순 — 커서로 이어 받으면 등록 최신순 그대로 빠짐·중복 없이 전부 나온다")
	void 등록순_커서로_전량을_순서대로_훑는다() {
		Long userId = (long) USER.getAndIncrement();
		LocalDate today = LocalDate.now(AppTime.KST);
		List<Long> registered = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			Long id = 전시(today.minusDays(i), today.plusDays(30 - i));
			bookmarkRepository.add(userId, id);
			registered.add(id);
		}
		// 등록 최신순 = 마지막에 담은 것이 맨 앞
		List<Long> expected = registered.reversed();

		List<Long> actual = 전페이지(userId, "latest", 3);

		assertThat(actual).containsExactlyElementsOf(expected);
	}

	@Test
	@DisplayName("종료임박순 — 커서로 이어 받으면 종료일 오름차순으로 빠짐·중복 없이 전부 나온다")
	void 종료임박순_커서로_전량을_순서대로_훑는다() {
		Long userId = (long) USER.getAndIncrement();
		LocalDate today = LocalDate.now(AppTime.KST);
		// 등록 순서와 종료일 순서를 일부러 어긋나게 만든다
		Long endsIn20 = 전시(today.minusDays(1), today.plusDays(20));
		Long endsIn5 = 전시(today.minusDays(1), today.plusDays(5));
		Long endsIn12 = 전시(today.minusDays(1), today.plusDays(12));
		Long endsIn1 = 전시(today.minusDays(1), today.plusDays(1));
		Long endsIn30 = 전시(today.minusDays(1), today.plusDays(30));
		List.of(endsIn20, endsIn5, endsIn12, endsIn1, endsIn30).forEach(id -> bookmarkRepository.add(userId, id));

		List<Long> actual = 전페이지(userId, "ending", 2);

		assertThat(actual).containsExactly(endsIn1, endsIn5, endsIn12, endsIn20, endsIn30);
	}

	@Test
	@DisplayName("totalCount는 삭제된 전시를 뺀 값이다")
	void totalCount는_살아있는_전시만_센다() {
		Long userId = (long) USER.getAndIncrement();
		LocalDate today = LocalDate.now(AppTime.KST);
		Long alive = 전시(today.minusDays(1), today.plusDays(10));
		Long removed = 전시(today.minusDays(1), today.plusDays(10));
		bookmarkRepository.add(userId, alive);
		bookmarkRepository.add(userId, removed);
		exhibitionRepository.findById(removed).ifPresent(e -> {
			e.delete();
			exhibitionRepository.save(e);
		});

		BookmarkResult.ListPage page = bookmarkFacade.list(new BookmarkCriteria.List(userId, "latest", null, 20));

		assertThat(page.totalCount()).isEqualTo(1);
		assertThat(page.content()).extracting(ExhibitionResult.ListItem::exhibitionId).containsExactly(alive);
	}

	@Test
	@DisplayName("북마크가 없으면 빈 목록이고 커서도 없다")
	void 북마크_없으면_빈_목록() {
		BookmarkResult.ListPage page = bookmarkFacade
				.list(new BookmarkCriteria.List((long) USER.getAndIncrement(), "latest", null, 20));

		assertThat(page.content()).isEmpty();
		assertThat(page.hasNext()).isFalse();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.totalCount()).isZero();
	}

	// ── 픽스처 ──────────────────────────────────────────────────────────────

	private Long 전시(LocalDate start, LocalDate end) {
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "관심목록검증관", ExhibitionRegion.INCHEON);
		Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
				"bookmark-" + SEQ.getAndIncrement(), "관심 목록 전시", start, end, ExhibitionCategory.PAINTING));
		return saved.getId();
	}

	/** 커서를 이어 받아 전 페이지를 훑는다 — 구현이 바뀌어도 이 계약은 유지돼야 한다. */
	private List<Long> 전페이지(Long userId, String sort, int size) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 50; page++) {
			BookmarkResult.ListPage result = bookmarkFacade
					.list(new BookmarkCriteria.List(userId, sort, cursor, size));
			result.content().forEach(item -> ids.add(item.exhibitionId()));
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}
		return ids;
	}
}
