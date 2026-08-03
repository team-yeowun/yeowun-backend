package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jakarta.persistence.EntityManagerFactory;
import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionFormat;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionTestFactory;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.support.time.AppTime;

/**
 * STEP 2 회귀 가드 — STEP 2의 핵심 리스크인 "목록과 /count의 필터가 정말 같은가"를 본다.
 * 구현자는 무필터·키워드 5건만 대조했다. 여기서는 section·region 다중·category 다중·date·조합·정렬·로그인까지 본다.
 *
 * <p>검증 방식은 하나다: 같은 필터로 목록을 커서로 <b>끝까지</b> 훑어 센 실제 건수 == {@code facade.count()}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.exhibition.enrich.scheduling-enabled=false",
		"spring.jpa.properties.hibernate.generate_statistics=true"
})
class ExhibitionCountParityTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionFacade exhibitionFacade;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Autowired
	EntityManagerFactory entityManagerFactory;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	private Statistics statistics;

	@BeforeEach
	void setUp() {
		statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
	}

	// ────────────────────────────────────────────────────────────────────────
	// 1) 필터 조합별 목록 전량 == /count
	// ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("section·region 다중·category 다중·date·조합 어디서도 목록 전량과 count가 같다 (비로그인)")
	void 필터_조합별_목록전량과_count가_같다_비로그인() {
		Fixture f = 픽스처만든다();
		필터조합_전부_대조(f, null);
	}

	@Test
	@DisplayName("로그인 요청(requesterId 있음)에서도 목록 전량과 count가 같다")
	void 필터_조합별_목록전량과_count가_같다_로그인() {
		Fixture f = 픽스처만든다();
		필터조합_전부_대조(f, 777_001L);
	}

	private void 필터조합_전부_대조(Fixture f, Long requesterId) {
		List<Case> cases = List.of(
				new Case("무필터(키워드만)", 검색(f.token, null, null, null, null, null, "latest", requesterId)),
				new Case("section=free", 검색(f.token, "free", null, null, null, null, "latest", requesterId)),
				new Case("section=ending-soon", 검색(f.token, "ending-soon", null, null, null, null, "latest", requesterId)),
				new Case("section=opening-this-month",
						검색(f.token, "opening-this-month", null, null, null, null, "latest", requesterId)),
				new Case("section=opening-this-month&period=week",
						검색(f.token, "opening-this-month", "week", null, null, null, "latest", requesterId)),
				new Case("region 단일", 검색(f.token, null, null, "SEOUL", null, null, "latest", requesterId)),
				new Case("region 다중", 검색(f.token, null, null, "SEOUL,BUSAN", null, null, "latest", requesterId)),
				new Case("region 다중 3개", 검색(f.token, null, null, "SEOUL,BUSAN,DAEGU", null, null, "latest", requesterId)),
				new Case("category 단일", 검색(f.token, null, null, null, "PAINTING", null, "latest", requesterId)),
				new Case("category 다중", 검색(f.token, null, null, null, "PAINTING,MEDIA", null, "latest", requesterId)),
				new Case("date 오늘", 검색(f.token, null, null, null, null, f.today, "latest", requesterId)),
				new Case("date 미래", 검색(f.token, null, null, null, null, f.today.plusDays(35), "latest", requesterId)),
				new Case("date 과거", 검색(f.token, null, null, null, null, f.today.minusDays(35), "latest", requesterId)),
				new Case("region+category", 검색(f.token, null, null, "SEOUL,BUSAN", "PAINTING", null, "latest", requesterId)),
				new Case("free+region", 검색(f.token, "free", null, "SEOUL", null, null, "latest", requesterId)),
				new Case("free+region 다중+category",
						검색(f.token, "free", null, "SEOUL,BUSAN", "PAINTING,MEDIA", null, "latest", requesterId)),
				new Case("ending-soon+region", 검색(f.token, "ending-soon", null, "SEOUL", null, null, "latest", requesterId)),
				new Case("date+region+category",
						검색(f.token, null, null, "SEOUL,DAEGU", "PAINTING", f.today, "latest", requesterId)),
				// 정렬 축을 바꿔도 count(정렬 안 받음)와 같아야 한다 — 목록의 sort가 count에 안 실리는 것이 안전한지
				new Case("sort=ending", 검색(f.token, null, null, null, null, null, "ending", requesterId)),
				new Case("sort=popular", 검색(f.token, null, null, null, null, null, "popular", requesterId)),
				new Case("sort=ending + free", 검색(f.token, "free", null, null, null, null, "ending", requesterId)),
				new Case("sort=popular + region 다중",
						검색(f.token, null, null, "SEOUL,BUSAN", null, null, "popular", requesterId)));

		List<String> 어긋남 = new ArrayList<>();
		List<String> 기록 = new ArrayList<>();
		for (Case c : cases) {
			List<Long> 전량 = 전페이지(c.criteria);
			long count = exhibitionFacade.count(c.criteria).count();
			기록.add("%-38s 목록전량=%d count=%d%s".formatted(c.name, 전량.size(), count,
					전량.size() == count ? "" : "   <<< 불일치"));
			if (전량.size() != count) {
				어긋남.add("%s: 목록 전량 %d vs count %d".formatted(c.name, 전량.size(), count));
			}
			if (전량.stream().distinct().count() != 전량.size()) {
				어긋남.add("%s: 목록 커서 순회에 중복 id가 있다".formatted(c.name));
			}
		}
		System.out.println("=== count parity (requesterId=" + (기록.isEmpty() ? "" : "") + ") ===");
		기록.forEach(System.out::println);

		assertThat(어긋남).as("목록과 /count의 필터가 어긋나면 '126개 전시 보기'가 거짓말이 된다").isEmpty();
	}

	// ────────────────────────────────────────────────────────────────────────
	// 2) sort는 count에 영향을 주면 안 된다 (구현자가 코드로만 확인한 지점)
	// ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("count는 sort를 무시한다 — latest·ending·popular·distance·null 모두 같은 수")
	void count는_sort를_무시한다() {
		Fixture f = 픽스처만든다();

		long 기준 = exhibitionFacade.count(검색(f.token, null, null, null, null, null, null, null)).count();
		for (String sort : new String[] { "latest", "ending", "popular", "distance", "LATEST", "이상한값" }) {
			long actual = exhibitionFacade.count(검색(f.token, null, null, null, null, null, sort, null)).count();
			assertThat(actual).as("sort=%s 일 때 count가 달라졌다 — 목록과 어긋난다", sort).isEqualTo(기준);
		}
		assertThat(기준).isGreaterThan(0);
	}

	// ────────────────────────────────────────────────────────────────────────
	// 3) 로그인해도 CUSTOM 전시는 목록에도 count에도 안 들어간다
	// ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("로그인 사용자 자신의 CUSTOM 전시는 목록에도 count에도 없다 — 둘의 판단이 같다")
	void 로그인_CUSTOM은_목록과_count에서_똑같이_빠진다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "커스텀토큰" + SEQ.getAndIncrement();
		Long ownerId = 888_001L;
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "커스텀검증관", ExhibitionRegion.SEOUL);

		// CATALOG 3건
		for (int i = 0; i < 3; i++) {
			exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, "gcustom-" + SEQ.getAndIncrement(),
					token + " 공개 전시 " + i, today.minusDays(i), today.plusDays(30), ExhibitionCategory.PAINTING));
		}
		// 본인 소유 CUSTOM 2건 (같은 키워드로 걸리도록)
		for (int i = 0; i < 2; i++) {
			exhibitionRepository.save(Exhibition.createCustom(ownerId, token + " 개인 전시 " + i, placeId, null,
					today.minusDays(i), today.plusDays(30), ExhibitionCategory.PAINTING, ExhibitionFormat.GROUP,
					"작가", null));
		}

		ExhibitionCriteria.Search 비로그인 = 검색(token, null, null, null, null, null, "latest", null);
		ExhibitionCriteria.Search 로그인 = 검색(token, null, null, null, null, null, "latest", ownerId);

		int 비로그인_목록 = 전페이지(비로그인).size();
		int 로그인_목록 = 전페이지(로그인).size();
		long 비로그인_count = exhibitionFacade.count(비로그인).count();
		long 로그인_count = exhibitionFacade.count(로그인).count();

		System.out.println("=== CUSTOM 노출 ===");
		System.out.println("비로그인 목록=%d count=%d / 로그인 목록=%d count=%d"
				.formatted(비로그인_목록, 비로그인_count, 로그인_목록, 로그인_count));

		assertThat(로그인_목록).as("로그인 목록 전량 == 로그인 count").isEqualTo((int) 로그인_count);
		assertThat(비로그인_목록).as("비로그인 목록 전량 == 비로그인 count").isEqualTo((int) 비로그인_count);
		assertThat(로그인_목록).as("CUSTOM은 탐색에 안 나온다 — 로그인/비로그인 결과가 같아야 한다").isEqualTo(비로그인_목록);
		assertThat(로그인_count).isEqualTo(3);
	}

	// ────────────────────────────────────────────────────────────────────────
	// 4) 요청당 JDBC 문 수 — 비로그인 2 / 로그인은 몇인지 사실만 기록
	// ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("요청당 JDBC 문 수 — 비로그인 2, 로그인은 관심 여부 배치가 1문 더 붙는다")
	void 요청당_JDBC_문수를_로그인_비로그인_둘_다_센다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "문수2토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "문수2검증관", ExhibitionRegion.BUSAN);
		for (int i = 0; i < 5; i++) {
			exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, "gstmt-" + SEQ.getAndIncrement(),
					token + " 전시 " + i, today.minusDays(i), today.plusDays(30), ExhibitionCategory.PAINTING));
		}

		long 비로그인 = 문수(검색(token, null, null, null, null, null, "latest", null), 2);
		long 로그인 = 문수(검색(token, null, null, null, null, null, "latest", 777_002L), 2);
		long count문수 = countStatements(검색(token, null, null, null, null, null, null, null));

		System.out.println("=== JDBC statements ===");
		System.out.println("목록(비로그인)=%d 목록(로그인)=%d /count=%d".formatted(비로그인, 로그인, count문수));

		// V49로 무료 판정이 전시 행에 굳으면서 가격 배치가 사라졌다(3→2).
		assertThat(비로그인).as("슬라이스 1 · 전시장 배치 1 = 2 (가격 배치가 되살아나면 3)").isEqualTo(2);
		assertThat(로그인).as("로그인은 관심 여부 배치 1문이 더 붙는다").isEqualTo(3);
		assertThat(count문수).as("총 건수는 SQL 한 문장").isEqualTo(1);
	}

	private long 문수(ExhibitionCriteria.Search criteria, int expectedPageSize) {
		long before = statistics.getPrepareStatementCount();
		ExhibitionResult.ListPage page = exhibitionFacade.search(criteria);
		long executed = statistics.getPrepareStatementCount() - before;
		assertThat(page.content()).hasSize(expectedPageSize);
		assertThat(page.hasNext()).as("페이지가 차야 옛 구현의 count가 드러난다").isTrue();
		return executed;
	}

	private long countStatements(ExhibitionCriteria.Search criteria) {
		long before = statistics.getPrepareStatementCount();
		exhibitionFacade.count(criteria);
		return statistics.getPrepareStatementCount() - before;
	}

	// ────────────────────────────────────────────────────────────────────────
	// 픽스처
	// ────────────────────────────────────────────────────────────────────────

	private record Case(String name, ExhibitionCriteria.Search criteria) {
	}

	private record Fixture(String token, LocalDate today) {
	}

	/**
	 * 키워드 토큰으로 격리된 전시 20건. 지역 3곳 · 카테고리 3종 · 무료/유료 · 진행중/미래/종료를 섞는다.
	 * 페이지 크기(2)보다 훨씬 많아 커서 순회가 여러 페이지를 탄다.
	 */
	private Fixture 픽스처만든다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "파리티토큰" + SEQ.getAndIncrement();

		Long seoul = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "파리티서울관", ExhibitionRegion.SEOUL);
		Long busan = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "파리티부산관", ExhibitionRegion.BUSAN);
		Long daegu = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "파리티대구관", ExhibitionRegion.DAEGU);
		Long[] places = { seoul, busan, daegu };
		ExhibitionCategory[] categories = { ExhibitionCategory.PAINTING, ExhibitionCategory.MEDIA,
				ExhibitionCategory.PHOTO };

		// [시작 offset, 종료 offset] — 진행중 · 곧 끝남 · 이번 달 개막 · 미래 · 종료 섞기
		int[][] spans = {
				{ -5, 30 }, { -5, 3 }, { -1, 2 }, { -20, 60 }, { -3, 5 },
				{ 2, 40 }, { 10, 50 }, { 35, 70 }, { -60, -30 }, { -50, -10 },
				{ -7, 7 }, { -2, 1 }, { -15, 15 }, { -30, 90 }, { 1, 6 },
				{ -4, 4 }, { -9, 25 }, { 20, 45 }, { -12, 12 }, { -6, 6 },
		};

		for (int i = 0; i < spans.length; i++) {
			Long placeId = places[i % places.length];
			ExhibitionCategory category = categories[i % categories.length];
			Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
					"gparity-" + SEQ.getAndIncrement(), token + " 전시 " + i,
					today.plusDays(spans[i][0]), today.plusDays(spans[i][1]), category));
			// 3건 중 1건은 무료, 1건은 유료, 1건은 상세 없음 — free 섹션 서브쿼리가 실제로 갈라지게
			if (i % 3 == 0) {
				exhibitionRepository.applyDetail(saved.getId(), "무료", null, null, LocalDateTime.now());
			} else if (i % 3 == 1) {
				exhibitionRepository.applyDetail(saved.getId(), "20,000원", null, null, LocalDateTime.now());
			}
		}
		return new Fixture(token, today);
	}

	private ExhibitionCriteria.Search 검색(String keyword, String section, String period, String region,
			String category, LocalDate date, String sort, Long requesterId) {
		return new ExhibitionCriteria.Search(keyword, section, period, region, category, date, sort, null, null,
				null, 2, requesterId);
	}

	/** 커서를 이어 받아 전 페이지를 훑는다. size=2라 여러 페이지를 탄다. */
	private List<Long> 전페이지(ExhibitionCriteria.Search base) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 200; page++) {
			ExhibitionCriteria.Search c = new ExhibitionCriteria.Search(base.keyword(), base.section(), base.period(),
					base.region(), base.category(), base.date(), base.sort(), base.lat(), base.lng(), cursor,
					base.size(), base.requesterId());
			ExhibitionResult.ListPage result = exhibitionFacade.search(c);
			result.content().forEach(item -> ids.add(item.exhibitionId()));
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}
		return ids;
	}
}
