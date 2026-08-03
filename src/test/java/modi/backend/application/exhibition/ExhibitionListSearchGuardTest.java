package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionTestFactory;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.support.time.AppTime;

/**
 * 목록/탐색 <b>유스케이스 한 번</b>이 무엇을 보장하는지 고정한다(@SpringBootTest + Testcontainers-MySQL).
 *
 * <p>리포지토리 단위 가드({@code ExhibitionSliceCountTest})는 슬라이스 한 문장만 본다. 사용자가 실제로 겪는 것은
 * 요청 하나이므로, 여기서는 <b>정문(파사드)</b>으로 들어가 요청 하나가 DB에 몇 번 말을 거는지를 센다.
 *
 * <p>두 가지를 고정한다.
 * <ol>
 *   <li><b>요청당 JDBC 문 수</b> — 슬라이스 1 · 전시장 배치 1 · 가격 배치 1 = 3.
 *       STEP 1에서 버려지던 count를 없애 5→4가 됐고, STEP 2에서 totalCount가 별도 엔드포인트로 갈라져 4→3이 됐다.
 *       둘 중 어느 하나라도 목록 경로로 되돌아오면 여기서 걸린다. 그래서 <b>페이지를 반드시 채운다</b> —
 *       대상이 페이지보다 적으면 Spring Data가 count를 생략해 옛 구현도 초록으로 나온다.</li>
 *   <li><b>커서 순회 무결성</b> — 슬라이스가 {@code Pageable}에서 fluent query로 바뀌면서 정렬이 그대로 전달되는지.
 *       ORDER BY와 키셋 경계가 어긋나면 행이 조용히 누락·중복된다. 시작일 <b>동률</b>을 일부러 만들어
 *       타이브레이커(id)까지 살아 있는지 본다.</li>
 * </ol>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.exhibition.enrich.scheduling-enabled=false",
		"spring.jpa.properties.hibernate.generate_statistics=true"
})
class ExhibitionListSearchGuardTest {

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

	@Test
	@DisplayName("목록 요청 하나는 페이지가 꽉 차도 JDBC 문 3개로 끝난다(버려지는 count도, 가격 배치도 없음)")
	void 목록_요청당_JDBC_문은_3개다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "문수토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "문수검증관", ExhibitionRegion.DAEGU);
		for (int i = 0; i < 5; i++) {
			전시(placeId, token + " 전시 " + i, today.minusDays(i), today.plusDays(30));
		}

		// size=3 → 슬라이스는 4건을 요청하는데 대상은 5건 — 반드시 페이지가 찬다(옛 구현이 count를 실행하던 조건).
		long before = statistics.getPrepareStatementCount();
		ExhibitionResult.ListPage page = exhibitionFacade.search(검색(token, null, 3));
		long executed = statistics.getPrepareStatementCount() - before;

		assertThat(page.content()).hasSize(3);
		assertThat(page.hasNext()).as("페이지가 차야 옛 구현의 count가 드러난다").isTrue();
		// 응답 계약 유지 결정으로 totalCount용 count 1문은 의도된 지출이다. 잡으려는 회귀는
		// "쓰지도 않는" count(Page가 만들고 버리던 것)와 가격 배치의 부활이다.
		assertThat(executed)
				.as("슬라이스 1 · 전시장 배치 1 · totalCount 1 = 3 (가격 배치가 되살아나면 4, 버려지는 count까지면 5)")
				.isEqualTo(3);
	}

	@Test
	@DisplayName("총 건수는 목록에서 갈라져 나왔고, 갈라져도 목록과 같은 필터를 세어 같은 수를 준다")
	void count는_같은_필터로_같은_수를_센다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "건수토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "건수검증관", ExhibitionRegion.INCHEON);
		for (int i = 0; i < 5; i++) {
			전시(placeId, token + " 전시 " + i, today.minusDays(i), today.plusDays(30));
		}

		// 목록으로 전 페이지를 훑어 얻은 실제 건수와 count 엔드포인트의 값이 같아야 한다 —
		// 두 경로가 같은 조건 조립을 공유한다는 것을 결과로 확인하는 자리다.
		int 목록_전량 = 전페이지(token, 2).size();

		long before = statistics.getPrepareStatementCount();
		ExhibitionResult.Count count = exhibitionFacade.count(검색(token, null, 20));
		long executed = statistics.getPrepareStatementCount() - before;

		assertThat(count.count()).as("목록을 전부 훑은 수와 같아야 한다").isEqualTo(목록_전량).isEqualTo(5);
		assertThat(count.exact()).as("루트 B의 count는 근사가 아니다").isTrue();
		assertThat(executed).as("총 건수는 SQL 한 문장으로 끝난다").isEqualTo(1);
	}

	@Test
	@DisplayName("커서로 전 페이지를 넘겨도 시작일·id 정렬 그대로 빠짐·중복 없이 전부 나온다")
	void 커서_순회는_정렬을_유지하고_누락_중복이_없다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "커서토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "커서검증관", ExhibitionRegion.DAEJEON);

		// 시작일 동률을 일부러 섞는다 — 타이브레이커(id)가 빠지면 여기서 행이 새거나 겹친다.
		int[] startOffsets = { 1, 1, 2, 3, 3, 3, 4 };
		Map<Long, LocalDate> startsById = new LinkedHashMap<>();
		for (int offset : startOffsets) {
			LocalDate start = today.minusDays(offset);
			startsById.put(전시(placeId, token + " 전시", start, today.plusDays(30)), start);
		}

		// 기대 순서 = 시작일 내림차순, 동률이면 id 내림차순(어댑터의 sortFor와 같은 규칙).
		List<Long> expected = startsById.keySet().stream()
				.sorted(Comparator.<Long, LocalDate>comparing(startsById::get)
						.thenComparing(Comparator.<Long>naturalOrder()).reversed())
				.toList();

		List<Long> actual = 전페이지(token, 2);

		assertThat(actual).as("페이지를 이어 붙이면 전량이 정렬 순서 그대로 나와야 한다")
				.containsExactlyElementsOf(expected);
		assertThat(actual).doesNotHaveDuplicates();
		// 총 건수는 이제 목록 응답에 없다 — 갈라져 나간 경로가 같은 수를 세는지로 대신 확인한다.
		assertThat(exhibitionFacade.count(검색(token, null, 2)).count()).isEqualTo(startOffsets.length);
	}

	@Test
	@DisplayName("종료임박순(오름차순)도 커서 순회에서 누락·중복이 없다")
	void 오름차순_커서_순회도_무결하다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "종료토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "종료검증관", ExhibitionRegion.GWANGJU);

		// 정렬 방향이 반대인 축도 같은 메커니즘(sortFor → fluent sortBy)을 타는지 본다.
		int[] endOffsets = { 3, 1, 3, 7, 1, 5 };
		Map<Long, LocalDate> endsById = new LinkedHashMap<>();
		for (int offset : endOffsets) {
			LocalDate end = today.plusDays(offset);
			endsById.put(전시(placeId, token + " 전시", today.minusDays(10), end), end);
		}

		// 기대 순서 = 종료일 오름차순, 동률이면 id 오름차순.
		List<Long> expected = endsById.keySet().stream()
				.sorted(Comparator.<Long, LocalDate>comparing(endsById::get)
						.thenComparing(Comparator.<Long>naturalOrder()))
				.toList();

		List<Long> actual = 전페이지(token, "ending", 2);

		assertThat(actual).containsExactlyElementsOf(expected);
		assertThat(actual).doesNotHaveDuplicates();
	}

	// ── 픽스처 ──────────────────────────────────────────────────────────────

	private Long 전시(Long placeId, String title, LocalDate start, LocalDate end) {
		Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
				"guard-" + SEQ.getAndIncrement(), title, start, end, ExhibitionCategory.PAINTING));
		return saved.getId();
	}

	private ExhibitionCriteria.Search 검색(String keyword, String cursor, int size) {
		return 검색(keyword, cursor, size, "latest");
	}

	private ExhibitionCriteria.Search 검색(String keyword, String cursor, int size, String sort) {
		return new ExhibitionCriteria.Search(keyword, null, null, null, null, null, sort, null, null,
				cursor, size, null);
	}

	private List<Long> 전페이지(String keyword, int size) {
		return 전페이지(keyword, "latest", size);
	}

	/** 커서를 이어 받아 전 페이지를 훑는다. */
	private List<Long> 전페이지(String keyword, String sort, int size) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 50; page++) {
			ExhibitionResult.ListPage result = exhibitionFacade.search(검색(keyword, cursor, size, sort));
			result.content().forEach(item -> ids.add(item.exhibitionId()));
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}
		return ids;
	}
}
