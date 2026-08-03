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
import modi.backend.support.response.Cursor;

/**
 * STEP 3 회귀 가드 — <b>관심 전시</b>(북마크) 종료임박순 커서가 종료일 미상 행을 만나도 온전한가.
 *
 * <p>기존 {@link BookmarkListPagingTest}는 종료일이 <b>전부 값이 있는</b> 픽스처만 쓴다. 그런데 V47은
 * 이 경로도 건드렸다 — {@code BookmarkFacade.encodeCursor}가 커서에 싣는 값을 도메인 값
 * ({@code getEndDate()}, 미상이면 null)에서 저장값({@code endDateKey()}, 미상이면 센티널)으로 바꿨다.
 * 관심 전시는 이번 실험이 <b>측정하지 않는 도메인</b>이므로 깨지지 않았음을 따로 보여야 한다.
 *
 * <p><b>미상 블록을 3건으로 두는 이유</b>(전시 목록 쪽에서 실제로 데인 함정): 미상이 뭉쳐 서는 블록이
 * 페이지 크기(2)보다 커야 <b>페이지 경계가 블록 한가운데 떨어진다</b>. 그래야 미상 행을 가리키는 커서가
 * 실제로 발급되고, 그 커서를 잘못 다루는 구현이 빨간불이 된다. 2건이면 블록이 마지막 페이지에 딱 맞아
 * 떨어져 그 커서가 아예 발급되지 않고 고장난 구현도 초록불로 지나간다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class BookmarkEndingCursorUnknownDateTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);
	private static final AtomicInteger USER = new AtomicInteger(31_000);

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
	@DisplayName("종료일 미상이 섞인 관심 전시 종료임박순 커서 순회에 중복·누락이 없고, 미상은 맨 뒤에 선다")
	void 종료일_미상이_섞여도_관심전시_커서가_온전하다() {
		Long userId = (long) USER.getAndIncrement();
		LocalDate today = LocalDate.now();

		Long endsIn1 = 전시(today.minusDays(1), today.plusDays(1));
		Long endsIn5 = 전시(today.minusDays(1), today.plusDays(5));
		Long endsIn12 = 전시(today.minusDays(1), today.plusDays(12));
		// 미상 블록 3건 — 페이지 크기(2)보다 커야 블록 한가운데를 가리키는 커서가 발급된다
		Long 미상A = 전시(today.minusDays(3), null);
		Long 미상B = 전시(today.minusDays(2), null);
		Long 미상C = 전시(null, null);

		List.of(endsIn12, 미상B, endsIn1, 미상C, endsIn5, 미상A).forEach(id -> bookmarkRepository.add(userId, id));

		List<Long> 순회 = 전페이지(userId, "ending", 2);

		System.out.println("=== STEP3 관심 전시 ENDING 커서 ===");
		System.out.println("순회=" + 순회.size() + " 고유=" + 순회.stream().distinct().count());

		assertThat(순회).as("중복이 없어야 한다").doesNotHaveDuplicates();
		assertThat(순회).as("6건 전부 나와야 한다(누락 0)").hasSize(6);
		// 종료일이 있는 것끼리는 오름차순, 미상 3건은 센티널(미래 무한)이라 맨 뒤 블록
		assertThat(순회.subList(0, 3)).containsExactly(endsIn1, endsIn5, endsIn12);
		assertThat(순회.subList(3, 6)).containsExactlyInAnyOrder(미상A, 미상B, 미상C);
	}

	@Test
	@DisplayName("정규화 전에 발급된 옛 커서(정렬 키가 비어 있음)도 관심 전시에서 미상 블록을 이어 받는다")
	void 관심전시도_옛_커서를_이어받는다() {
		Long userId = (long) USER.getAndIncrement();
		LocalDate today = LocalDate.now();

		Long endsIn1 = 전시(today.minusDays(1), today.plusDays(1));
		Long 미상A = 전시(today.minusDays(3), null);
		Long 미상B = 전시(today.minusDays(2), null);
		Long 미상C = 전시(null, null);
		List.of(endsIn1, 미상A, 미상B, 미상C).forEach(id -> bookmarkRepository.add(userId, id));

		List<Long> 전체 = 전페이지(userId, "ending", 2);
		List<Long> 미상블록 = 전체.subList(1, 4);

		// 배포 순간 클라이언트가 쥐고 있던 커서 — 미상 행을 가리키는데 정렬 키가 비어 있다.
		String 옛커서 = Cursor.of("ending", null, 미상블록.get(0)).encode();

		List<Long> 이어받은것 = new ArrayList<>();
		String cursor = 옛커서;
		for (int page = 0; page < 50; page++) {
			BookmarkResult.ListPage result = bookmarkFacade
					.list(new BookmarkCriteria.List(userId, "ending", cursor, 2));
			result.content().forEach(item -> 이어받은것.add(item.exhibitionId()));
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}

		assertThat(이어받은것)
				.as("옛 커서 다음부터 미상 블록의 나머지가 정확히 이어져야 한다(빈 페이지로 끊기면 안 된다)")
				.containsExactlyElementsOf(미상블록.subList(1, 미상블록.size()));
	}

	// ── 픽스처 ──────────────────────────────────────────────────────────────

	private Long 전시(LocalDate start, LocalDate end) {
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "관심미상검증관", ExhibitionRegion.INCHEON);
		Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
				"bmunknown-" + SEQ.getAndIncrement(), "관심 미상 전시", start, end, ExhibitionCategory.PAINTING));
		return saved.getId();
	}

	private List<Long> 전페이지(Long userId, String sort, int size) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 50; page++) {
			BookmarkResult.ListPage result = bookmarkFacade
					.list(new BookmarkCriteria.List(userId, sort, cursor, size));
			result.content().stream().map(ExhibitionResult.ListItem::exhibitionId).forEach(ids::add);
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}
		return ids;
	}
}
