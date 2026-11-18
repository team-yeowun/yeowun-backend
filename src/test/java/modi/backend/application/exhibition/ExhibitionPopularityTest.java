package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.application.bookmark.BookmarkCriteria;
import modi.backend.application.bookmark.BookmarkFacade;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionTestFactory;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.support.time.AppTime;

/**
 * 인기순 반정규화(V61): 인기 점수 = 북마크 수 × 0.7 + 조회수 × 0.3.
 *
 * <p>조회수와 북마크 수는 전시 행에 있고, 앱이 원장 쓰기(상세 조회, 북마크 행)와 <b>같은 요청에서 DB 안에서 바로</b>
 * 더한다(count = count + 1). 점수는 MySQL이 두 수에서 계산하는 생성 컬럼이라 앱이 쓰지 않는다.
 * 이 테스트는 그 세 가지(정렬, 조회수, 북마크 수)가 실제 DB에서 맞물리는지 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionPopularityTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);
	private static final AtomicInteger USER = new AtomicInteger(71_000);

	@Autowired
	ExhibitionFacade exhibitionFacade;
	@Autowired
	BookmarkFacade bookmarkFacade;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("인기순은 점수(북마크 × 0.7 + 조회 × 0.3) 내림차순, 동점은 id 내림차순이고 커서로 이어 받으면 빠짐없이 나온다")
	void 인기순은_점수_내림차순이고_커서로_전부_나온다() {
		String keyword = "인기점수" + UUID.randomUUID().toString().substring(0, 8);
		Long views10 = 전시(keyword);       // 0 × 0.7 + 10 × 0.3 = 3.0
		Long bookmarks5a = 전시(keyword);   // 5 × 0.7 + 0 × 0.3 = 3.5
		Long both1 = 전시(keyword);         // 1 × 0.7 + 1 × 0.3 = 1.0
		Long bookmarks5b = 전시(keyword);   // 3.5 (bookmarks5a와 동점, id가 더 큼)
		Long none = 전시(keyword);          // 0
		수(views10, 10, 0);
		수(bookmarks5a, 0, 5);
		수(both1, 1, 1);
		수(bookmarks5b, 0, 5);

		List<Long> actual = 인기순_전페이지(keyword, 2);

		// 조회수만 봤다면 views10이 맨 앞이었다. 북마크 5개가 조회 10회보다 위다.
		assertThat(actual).containsExactly(bookmarks5b, bookmarks5a, views10, both1, none);
	}

	@Test
	@DisplayName("상세를 볼 때마다 조회수가 DB에서 1씩 오르고, 캐시에서 꺼낸 상세여도 응답은 오른 값이다")
	void 상세_조회마다_조회수가_DB에서_오른다() {
		Long id = 전시("조회수검증");

		long first = exhibitionFacade.getDetail(new ExhibitionCriteria.Detail(id, null)).viewCount();
		long second = exhibitionFacade.getDetail(new ExhibitionCriteria.Detail(id, null)).viewCount();

		assertThat(first).isEqualTo(1);
		assertThat(second).isEqualTo(2);
		assertThat(viewCount(id)).isEqualTo(2);
	}

	@Test
	@DisplayName("북마크 수는 상태가 바뀔 때만 ±1 한다(이미 등록이면 등록, 이미 해제면 해제가 수를 바꾸지 않는다)")
	void 북마크_수는_상태가_바뀔_때만_움직인다() {
		Long id = 전시("북마크수검증");
		Long user = (long) USER.getAndIncrement();
		BookmarkCriteria.Toggle toggle = new BookmarkCriteria.Toggle(user, id);

		bookmarkFacade.add(toggle);
		bookmarkFacade.add(toggle);
		assertThat(bookmarkCount(id)).isEqualTo(1);

		bookmarkFacade.remove(toggle);
		bookmarkFacade.remove(toggle);
		assertThat(bookmarkCount(id)).isZero();

		bookmarkFacade.add(toggle);
		assertThat(bookmarkCount(id)).isEqualTo(1);
		assertThat(score(id)).isEqualTo("0.7");
	}

	@Test
	@DisplayName("같은 사용자의 해제된 북마크를 8개 요청이 동시에 되살려도 수는 1만 오른다")
	void 동시_재등록도_한_번만_센다() throws Exception {
		Long id = 전시("동시재등록검증");
		Long user = (long) USER.getAndIncrement();
		BookmarkCriteria.Toggle toggle = new BookmarkCriteria.Toggle(user, id);
		bookmarkFacade.add(toggle);
		bookmarkFacade.remove(toggle);

		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				start.await();
				return bookmarkFacade.add(toggle);
			}));
		}
		start.countDown();
		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(bookmarkCount(id)).isEqualTo(1);
	}

	// ── 픽스처 ──────────────────────────────────────────────────────────────

	private Long 전시(String title) {
		LocalDate today = LocalDate.now(AppTime.KST);
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "인기순검증관", ExhibitionRegion.INCHEON);
		Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
				"popularity-" + SEQ.getAndIncrement() + "-" + UUID.randomUUID(), title,
				today.minusDays(1), today.plusDays(30), ExhibitionCategory.PAINTING));
		return saved.getId();
	}

	private void 수(Long id, long views, long bookmarks) {
		jdbc.update("update exhibitions set our_view_count = ?, bookmark_count = ? where id = ?", views, bookmarks, id);
	}

	private long viewCount(Long id) {
		return jdbc.queryForObject("select our_view_count from exhibitions where id = ?", Long.class, id);
	}

	private long bookmarkCount(Long id) {
		return jdbc.queryForObject("select bookmark_count from exhibitions where id = ?", Long.class, id);
	}

	private String score(Long id) {
		return jdbc.queryForObject("select popularity_score from exhibitions where id = ?", String.class, id);
	}

	/** 커서를 이어 받아 인기순 전 페이지를 훑는다(키워드로 이 테스트의 전시만 고른다). */
	private List<Long> 인기순_전페이지(String keyword, int size) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 20; page++) {
			ExhibitionResult.ListPage result = exhibitionFacade.search(new ExhibitionCriteria.Search(keyword, null,
					null, null, null, null, "popular", null, null, cursor, size, null));
			result.content().forEach(item -> ids.add(item.exhibitionId()));
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}
		return ids;
	}
}
