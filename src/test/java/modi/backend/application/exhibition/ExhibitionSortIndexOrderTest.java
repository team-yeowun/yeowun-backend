package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
 * STEP 4 회귀 가드 — 정렬을 <b>인덱스가 대신하게 된 뒤에도</b> 커서 순회의 순서가 그대로인가.
 *
 * <p>V48은 {@code (start_date, id)} · {@code (end_date, id)}를 만들고
 * {@code idx_exhibitions_dates(start_date, end_date)}를 지운다. 이때 깨질 수 있는 것은 <b>집합이 아니라 순서</b>다.
 * 인덱스가 ORDER BY를 대신하면 실제 반환 순서는 <b>인덱스에 적힌 순서</b>가 되므로,
 * 커서 경계({@code ExhibitionSpecifications.latestBoundary}/{@code endingBoundary})가 보는 순서와
 * 한 칸이라도 어긋나면 행이 중복되거나 빠진다.
 *
 * <p><b>이 테스트가 겨냥하는 것은 동점(tie)이다.</b> 지운 {@code (start_date, end_date)}가 정렬에 못 쓰이던
 * 이유가 바로 그것이었다 — 시작일이 같은 행들의 인덱스 내부 순서가 {@code id}가 아니라 {@code end_date}였다.
 * 그래서 픽스처는 <b>같은 시작일에 여러 행</b>, <b>같은 종료일에 여러 행</b>을 두고, 그 동점 행들의
 * {@code end_date}/{@code start_date}를 일부러 id 순서와 <b>반대로</b> 매긴다.
 * 타이브레이커가 id가 아닌 무엇으로 풀리면 기대 순서와 어긋나 빨간불이 된다.
 *
 * <p>기존 {@code ExhibitionPeriodSentinelTest}는 "중복·누락 없음"(집합)까지만 본다.
 * 여기서는 <b>전체 순회 순서가 기대 전순서와 글자 그대로 같은지</b>를 본다 — 집합이 맞아도 순서가 틀릴 수 있다.
 *
 * <p>페이지 크기는 3이고 픽스처는 12건이라 <b>동점 블록 한가운데에서 페이지가 끊긴다</b>.
 * 그래야 동점 행을 가리키는 커서가 실제로 발급되고, 경계가 틀린 구현이 빨간불이 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionSortIndexOrderTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);
	private static final int PAGE = 3;

	@Autowired
	ExhibitionFacade exhibitionFacade;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("최신순: 시작일 동점이 섞인 커서 순회가 (시작일 DESC, id DESC)와 글자 그대로 같다")
	void 최신순_커서_순회가_기대_전순서와_같다() {
		Fixture f = 픽스처만든다();

		List<Long> 기대 = f.rows.stream()
				.sorted(Comparator.comparing(Row::startKey).reversed()
						.thenComparing(Comparator.comparing(Row::id).reversed()))
				.map(Row::id).toList();

		List<Long> 실제 = 전페이지(f.token, "latest");

		assertThat(실제).as("최신순 커서 순회는 정렬 인덱스가 세운 순서를 그대로 따라와야 한다").containsExactlyElementsOf(기대);
		assertThat(실제).doesNotHaveDuplicates();
		assertThat(실제).hasSize(f.rows.size());
	}

	@Test
	@DisplayName("종료임박순: 종료일 동점이 섞인 커서 순회가 (종료일 ASC, id ASC)와 글자 그대로 같다")
	void 종료순_커서_순회가_기대_전순서와_같다() {
		Fixture f = 픽스처만든다();

		List<Long> 기대 = f.rows.stream()
				.sorted(Comparator.comparing(Row::endKey).thenComparing(Row::id))
				.map(Row::id).toList();

		List<Long> 실제 = 전페이지(f.token, "ending");

		assertThat(실제).as("종료순 커서 순회는 정렬 인덱스가 세운 순서를 그대로 따라와야 한다").containsExactlyElementsOf(기대);
		assertThat(실제).doesNotHaveDuplicates();
		assertThat(실제).hasSize(f.rows.size());
	}

	/**
	 * 센티널(미상)이 정렬 양 끝에서 <b>기대한 자리에</b> 서는지. V47이 NULL을 없앴으므로
	 * "NULL을 앞에 놓는 MySQL"과 "NULL을 뒤로 취급하는 경계"가 어긋날 여지 자체가 사라졌는데,
	 * 그 사실이 인덱스 도입 뒤에도 유지되는지 고정한다.
	 */
	@Test
	@DisplayName("센티널: 시작일 미상은 최신순 맨 뒤, 종료일 미상은 종료순 맨 뒤에 선다")
	void 센티널이_두_축_모두에서_맨_뒤에_선다() {
		Fixture f = 픽스처만든다();

		List<Long> 최신 = 전페이지(f.token, "latest");
		List<Long> 종료 = 전페이지(f.token, "ending");

		assertThat(f.labelById.get(최신.get(최신.size() - 1)))
				.as("시작일 센티널(1000-01-01)은 DESC의 맨 뒤다").isEqualTo("시작미상");
		assertThat(f.labelById.get(종료.get(종료.size() - 1)))
				.as("종료일 센티널(9999-12-31)은 ASC의 맨 뒤다").isEqualTo("종료미상");
	}

	// ────────────────────────────────────────────────────────────────────────
	// 픽스처
	// ────────────────────────────────────────────────────────────────────────

	private record Row(Long id, LocalDate startKey, LocalDate endKey) {
	}

	private record Fixture(String token, List<Row> rows, Map<Long, String> labelById) {
	}

	/**
	 * 12건. 두 축 모두에서 동점이 생기도록 짠다.
	 *
	 * <ul>
	 *   <li><b>시작일 동점 4건</b>(A1~A4) — 이들의 종료일을 id와 <b>역순</b>으로 매긴다.
	 *       타이브레이커가 id가 아니라 end_date로 풀리면(= 지운 인덱스의 순서) 기대와 어긋난다.</li>
	 *   <li><b>종료일 동점 4건</b>(B1~B4) — 이들의 시작일을 id와 역순으로 매긴다. 위와 대칭.</li>
	 *   <li>나머지 4건은 동점 블록 앞뒤에 흩어 두고, 미상 2건을 섞어 센티널 자리를 확인한다.</li>
	 * </ul>
	 *
	 * <p>전부 오늘 기준 진행 중이라 {@code ongoingOn} 필터에 걸리지 않는다 — 이 테스트는 <b>순서만</b> 본다.
	 */
	private Fixture 픽스처만든다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "정렬토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "정렬검증관", ExhibitionRegion.SEOUL);

		LocalDate 동점시작 = today.minusDays(10);
		LocalDate 동점종료 = today.plusDays(40);

		Object[][] rows = {
				// 시작일 동점 4건 — 종료일은 id 증가와 반대로 줄어든다
				{ "A1", 동점시작, today.plusDays(80) },
				{ "A2", 동점시작, today.plusDays(70) },
				{ "A3", 동점시작, today.plusDays(60) },
				{ "A4", 동점시작, today.plusDays(50) },
				// 종료일 동점 4건 — 시작일은 id 증가와 반대로 줄어든다
				{ "B1", today.minusDays(4), 동점종료 },
				{ "B2", today.minusDays(5), 동점종료 },
				{ "B3", today.minusDays(6), 동점종료 },
				{ "B4", today.minusDays(7), 동점종료 },
				// 동점 블록 바깥
				{ "C1", today.minusDays(1), today.plusDays(90) },
				{ "C2", today.minusDays(30), today.plusDays(20) },
				// 미상 — 센티널 자리 확인용
				{ "시작미상", null, today.plusDays(30) },
				{ "종료미상", today.minusDays(20), null },
		};

		List<Row> saved = new ArrayList<>();
		Map<Long, String> labelById = new LinkedHashMap<>();
		for (Object[] row : rows) {
			Exhibition e = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
					"gsort-" + SEQ.getAndIncrement(), token + " " + row[0],
					(LocalDate) row[1], (LocalDate) row[2], ExhibitionCategory.PAINTING));
			// 정렬 키는 저장값(센티널 포함)이다 — 응답용 게터(null 마스킹)를 쓰면 기대 순서가 다른 축을 본다.
			saved.add(new Row(e.getId(), e.startDateKey(), e.endDateKey()));
			labelById.put(e.getId(), (String) row[0]);
		}
		return new Fixture(token, List.copyOf(saved), labelById);
	}

	/** 커서를 이어 받아 전 페이지를 훑는다. size=3이라 동점 블록 한가운데에서 페이지가 끊긴다. */
	private List<Long> 전페이지(String token, String sort) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 200; page++) {
			ExhibitionCriteria.Search c = new ExhibitionCriteria.Search(token, null, null, null, null, null,
					sort, null, null, cursor, PAGE, null);
			ExhibitionResult.ListPage result = exhibitionFacade.search(c);
			result.content().forEach(item -> ids.add(item.exhibitionId()));
			if (!result.hasNext()) {
				return ids;
			}
			cursor = result.nextCursor();
		}
		throw new IllegalStateException("커서 순회가 200페이지를 넘었다 — 경계가 진행하지 않는다");
	}
}
