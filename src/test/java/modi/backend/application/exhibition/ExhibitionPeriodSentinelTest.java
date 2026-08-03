package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
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
import modi.backend.support.response.Cursor;
import modi.backend.support.time.AppTime;

/**
 * STEP 3 회귀 가드 — 기간 NULL을 센티널로 정규화(V47)해도 <b>보이는 것이 하나도 안 바뀌는가</b>.
 *
 * <p>이 단계는 술어의 모양만 바꾼다({@code (start IS NULL OR start <= ?)} → {@code start <= ?}).
 * 그래서 여기서 보는 것은 성능이 아니라 <b>동치성</b>이다:
 * <ol>
 *   <li>진행 중 판정이 날짜 경계(시작 당일·종료 당일·미래·과거)와 미상 조합에서 예전과 같은 집합을 내는가</li>
 *   <li>목록과 {@code /count}가 여전히 같은 집합을 세는가</li>
 *   <li>센티널이 <b>밖으로 새지 않는가</b> — 응답의 startDate/endDate와 dDay</li>
 *   <li>종료일 미상 행이 섞인 ENDING 커서 순회에서 중복·누락이 없는가</li>
 * </ol>
 *
 * <p><b>이 테스트는 옛 구현을 빨간불로 만들지 못한다</b> — 옛 구현과 같은 답을 내는 것이 목표이기 때문이다.
 * 유효성은 "새 구현을 망가뜨리면 빨간불이 되는가"(변이 주입)로 보였다. 산출물의 반증 절에 기록한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionPeriodSentinelTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionFacade exhibitionFacade;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Autowired
	modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository exhibitionQueryRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	// ────────────────────────────────────────────────────────────────────────
	// 1) 진행 중 판정 — 날짜 경계 × 미상 조합
	// ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("진행 중 판정: 시작 당일·종료 당일은 포함, 미래 시작·과거 종료는 제외, 미상은 관대하게 포함")
	void 진행중_판정이_날짜_경계와_미상에서_예전과_같다() {
		Fixture f = 픽스처만든다();
		LocalDate today = f.today;

		// 오늘 기준 — 시작 당일(D-0 개막)·종료 당일(D-0 폐막)이 포함되는 것이 기존 동작이다.
		assertThat(라벨들(f, today)).containsExactlyInAnyOrder(
				"시작당일", "종료당일", "진행중", "시작미상", "시작미상2", "종료미상", "종료미상2", "둘다미상");

		// 내일 기준 — 종료 당일 전시는 빠지고 내일 개막 전시가 들어온다(경계가 하루 움직이는지)
		assertThat(라벨들(f, today.plusDays(1))).containsExactlyInAnyOrder(
				"시작당일", "내일개막", "진행중", "시작미상", "시작미상2", "종료미상", "종료미상2", "둘다미상");

		// 어제 기준 — 어제 폐막 전시가 들어오고 오늘 개막 전시가 빠진다
		assertThat(라벨들(f, today.minusDays(1))).containsExactlyInAnyOrder(
				"어제폐막", "종료당일", "진행중", "시작미상", "시작미상2", "종료미상", "종료미상2", "둘다미상");

		// 아주 먼 과거 — 시작 미상(과거 무한)만이 "이미 시작"으로 남고, 종료 미상만으로는 부족하다
		assertThat(라벨들(f, today.minusYears(50)))
				.containsExactlyInAnyOrder("시작미상", "시작미상2", "둘다미상");

		// 아주 먼 미래 — 종료 미상(미래 무한)만이 "아직 진행"으로 남는다
		assertThat(라벨들(f, today.plusYears(50)))
				.containsExactlyInAnyOrder("종료미상", "종료미상2", "둘다미상");
	}

	@Test
	@DisplayName("검색(notEnded) 판정: 아직 안 끝난 전시만 — 종료 미상은 남고 과거 종료는 빠진다")
	void 검색은_끝난_전시만_걸러낸다() {
		Fixture f = 픽스처만든다();

		// date 없이 키워드만 주면 ongoingOn이 아니라 notEndedOn(today)이 선다.
		assertThat(라벨들(f, null)).containsExactlyInAnyOrder(
				"시작당일", "종료당일", "내일개막", "미래개막", "진행중",
				"시작미상", "시작미상2", "종료미상", "종료미상2", "둘다미상");
	}

	// ────────────────────────────────────────────────────────────────────────
	// 2) 목록 전량 == /count (STEP 2의 불변을 STEP 3이 깨지 않았는가)
	// ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("날짜 경계마다 목록 전량과 count가 여전히 같다")
	void 목록전량과_count가_날짜경계에서도_같다() {
		Fixture f = 픽스처만든다();
		List<LocalDate> 기준일들 = new ArrayList<>();
		기준일들.add(null);
		for (int offset : new int[] { -1, 0, 1, 2, -30, 30 }) {
			기준일들.add(f.today.plusDays(offset));
		}

		System.out.println("=== STEP3 기간 경계 parity ===");
		for (LocalDate date : 기준일들) {
			long 목록전량 = 전페이지(검색(f.token, date, "latest")).size();
			long count = exhibitionFacade.count(검색(f.token, date, null)).count();
			System.out.println("date=%-12s 목록전량=%d count=%d".formatted(String.valueOf(date), 목록전량, count));
			assertThat(count).as("date=" + date).isEqualTo(목록전량);
		}
	}

	// ────────────────────────────────────────────────────────────────────────
	// 3) 센티널 누출 — 여기가 이 STEP의 진짜 위험 지점이다
	// ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("센티널은 DB에만 있다 — 응답의 startDate·endDate·dDay 어디에도 1000-01-01/9999-12-31이 안 보인다")
	void 센티널이_응답으로_새지_않는다() {
		Fixture f = 픽스처만든다();

		Map<String, ExhibitionResult.ListItem> byLabel = new LinkedHashMap<>();
		for (ExhibitionResult.ListItem item : 전페이지항목(검색(f.token, null, "latest"))) {
			byLabel.put(f.labelById.get(item.exhibitionId()), item);
		}

		ExhibitionResult.ListItem 시작미상 = byLabel.get("시작미상");
		assertThat(시작미상.startDate()).as("시작 미상은 응답에서 여전히 null이어야 한다").isNull();
		assertThat(시작미상.endDate()).isEqualTo(f.today.plusDays(10));

		ExhibitionResult.ListItem 종료미상 = byLabel.get("종료미상");
		assertThat(종료미상.startDate()).isEqualTo(f.today.minusDays(10));
		assertThat(종료미상.endDate()).as("종료 미상은 응답에서 여전히 null이어야 한다").isNull();
		assertThat(종료미상.dDay()).as("종료 미상의 D-day는 292만일이 아니라 null이다").isNull();

		ExhibitionResult.ListItem 둘다미상 = byLabel.get("둘다미상");
		assertThat(둘다미상.startDate()).isNull();
		assertThat(둘다미상.endDate()).isNull();
		assertThat(둘다미상.dDay()).isNull();

		// 값이 있는 전시는 그대로다(마스킹이 멀쩡한 날짜까지 지우면 안 된다)
		ExhibitionResult.ListItem 진행중 = byLabel.get("진행중");
		assertThat(진행중.startDate()).isEqualTo(f.today.minusDays(10));
		assertThat(진행중.endDate()).isEqualTo(f.today.plusDays(10));
		assertThat(진행중.dDay()).isEqualTo(10);

		// 어떤 항목에도 센티널이 실려 있으면 안 된다
		assertThat(byLabel.values()).allSatisfy(item -> {
			assertThat(item.startDate()).isNotEqualTo(Exhibition.START_DATE_UNKNOWN);
			assertThat(item.endDate()).isNotEqualTo(Exhibition.END_DATE_UNKNOWN);
		});
	}

	@Test
	@DisplayName("DB에는 NULL이 아니라 센티널이 적힌다 — NOT NULL 컬럼을 왕복해도 도메인 값은 null 그대로")
	void 저장은_센티널_도메인은_null이다() {
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "센티널왕복관", ExhibitionRegion.SEOUL);
		Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
				"gsent-" + SEQ.getAndIncrement(), "왕복 전시", null, null, ExhibitionCategory.PAINTING));

		Exhibition reloaded = exhibitionRepository.findById(saved.getId()).orElseThrow();

		// NOT NULL 컬럼에 실제로 값이 들어갔다(들어가지 않았다면 insert 자체가 터진다)
		assertThat(reloaded.startDateKey()).isEqualTo(Exhibition.START_DATE_UNKNOWN);
		assertThat(reloaded.endDateKey()).isEqualTo(Exhibition.END_DATE_UNKNOWN);
		// 그런데 도메인이 내주는 값은 예전과 같은 null이다
		assertThat(reloaded.getStartDate()).isNull();
		assertThat(reloaded.getEndDate()).isNull();
		assertThat(reloaded.dDay(LocalDate.now(AppTime.KST))).isNull();
	}

	// ────────────────────────────────────────────────────────────────────────
	// 4) ENDING 커서 — 종료일 미상 행이 섞여도 중복·누락이 없는가
	//    (STEP 2 검증자가 남긴 "정렬 컬럼 null 행의 커서 경계" 관찰 항목)
	// ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("종료일 미상이 섞인 ENDING 커서 순회에 중복·누락이 없다")
	void 종료일_미상이_섞여도_ending_커서가_온전하다() {
		Fixture f = 픽스처만든다();

		List<Long> 순회 = 전페이지(검색(f.token, null, "ending"));
		long count = exhibitionFacade.count(검색(f.token, null, null)).count();

		System.out.println("=== STEP3 ENDING 커서 ===");
		System.out.println("순회=%d 고유=%d count=%d".formatted(순회.size(), Set고유(순회), count));

		assertThat(순회).doesNotHaveDuplicates();
		assertThat((long) 순회.size()).isEqualTo(count);

		// 종료일 미상은 "미래 무한"이라 오름차순에서 맨 뒤에 선다 — 예전의 nulls last 의도와 같은 자리다.
		assertThat(f.labelById.get(순회.get(순회.size() - 1)))
				.as("ENDING 오름차순의 마지막은 종료일 미상 블록이어야 한다")
				.isIn("종료미상", "종료미상2", "둘다미상");
	}

	@Test
	@DisplayName("시작일 미상이 섞인 LATEST 커서 순회에도 중복·누락이 없다")
	void 시작일_미상이_섞여도_latest_커서가_온전하다() {
		Fixture f = 픽스처만든다();

		List<Long> 순회 = 전페이지(검색(f.token, null, "latest"));
		long count = exhibitionFacade.count(검색(f.token, null, null)).count();

		assertThat(순회).doesNotHaveDuplicates();
		assertThat((long) 순회.size()).isEqualTo(count);
		assertThat(f.labelById.get(순회.get(순회.size() - 1)))
				.as("LATEST 내림차순의 마지막은 시작일 미상 블록이어야 한다")
				.isIn("시작미상", "시작미상2", "둘다미상");
	}

	@Test
	@DisplayName("정규화 전에 발급된 옛 커서(정렬 키가 비어 있음)도 미상 블록을 그대로 이어 받는다")
	void 정규화_전에_발급된_커서도_이어받는다() {
		Fixture f = 픽스처만든다();

		// 배포 순간 사용자가 손에 쥐고 있던 커서를 재현한다. 예전엔 종료일이 NULL인 행을 가리키는 커서의
		// 정렬 키가 비어 있었다(= nulls 블록). 이제는 그런 커서가 새로 발급되지 않으므로 직접 만들어 넣는다.
		List<Long> 전체 = 전페이지(검색(f.token, null, "ending"));
		List<Long> 미상블록 = 전체.stream()
				.filter(id -> List.of("종료미상", "종료미상2", "둘다미상").contains(f.labelById.get(id)))
				.toList();
		assertThat(미상블록).as("미상 블록이 2건 이하면 이 검증이 성립하지 않는다").hasSizeGreaterThanOrEqualTo(3);

		Long 기준 = 미상블록.get(0);
		String 옛커서 = Cursor.of("ending", null, 기준).encode();

		List<Long> 이어받은것 = new ArrayList<>();
		String cursor = 옛커서;
		for (int page = 0; page < 200; page++) {
			ExhibitionCriteria.Search c = new ExhibitionCriteria.Search(f.token, null, null, null, null, null,
					"ending", null, null, cursor, 2, null);
			ExhibitionResult.ListPage result = exhibitionFacade.search(c);
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

	// ────────────────────────────────────────────────────────────────────────
	// 5) 홈 배너 — 날짜 미상은 배너에 올리지 않는다 (사용자 결정 B, 2026-07-30)
	// ────────────────────────────────────────────────────────────────────────

	/**
	 * 배너는 목록과 달리 미상을 <b>포함하지 않는다</b>. V47 이전에는 날짜가 {@code NULL}이라
	 * 3치 논리로 저절로 빠졌는데, 센티널로 굳히면서 {@code 1000-01-01 ≤ 오늘 ≤ 9999-12-31}이 참이 되어
	 * 미상 전시가 배너에 새로 들어왔다. 그 동작 변화를 되돌린 것을 여기서 고정한다.
	 *
	 * <p><b>limit을 크게 줘서 조회수 순위와 무관하게 만든다</b> — 미상 행의 조회수가 0이면
	 * 상위 3개에 애초에 못 드니 "제외됐다"를 증명하지 못한다(아무것도 못 잡는 테스트가 된다).
	 * 전량을 받아 <b>포함 여부</b>만 보면 순위와 무관하게 성립한다.
	 */
	@Test
	@DisplayName("홈 배너: 날짜 미상 전시는 후보에 아예 들어오지 않는다(목록에는 들어온다)")
	void 배너는_날짜미상을_제외한다() {
		Fixture f = 픽스처만든다();
		LocalDate today = f.today;

		List<Long> 배너후보 = exhibitionQueryRepository.findOngoingCatalogTopByViews(today, 10_000)
				.stream().map(Exhibition::getId).toList();

		List<Long> 미상들 = f.labelById.entrySet().stream()
				.filter(e -> List.of("시작미상", "시작미상2", "종료미상", "종료미상2", "둘다미상").contains(e.getValue()))
				.map(Map.Entry::getKey).toList();
		assertThat(미상들).hasSize(5);

		assertThat(배너후보).as("날짜 미상 전시는 배너 후보에 없어야 한다").doesNotContainAnyElementsOf(미상들);

		// 대조 — 날짜가 멀쩡한 진행 중 전시는 후보에 있어야 한다.
		// (이게 없으면 "쿼리가 아무것도 안 준다"도 통과해 버린다)
		Long 진행중 = f.labelById.entrySet().stream()
				.filter(e -> e.getValue().equals("진행중")).map(Map.Entry::getKey).findFirst().orElseThrow();
		assertThat(배너후보).as("날짜가 있는 진행 중 전시는 배너 후보에 있어야 한다").contains(진행중);

		// 그리고 같은 미상 전시가 목록에는 여전히 나온다 — 배너만 다른 규칙을 쓴다는 뜻이다.
		assertThat(전페이지(검색(f.token, today, "latest"))).containsAll(미상들);
	}

	// ────────────────────────────────────────────────────────────────────────
	// 픽스처
	// ────────────────────────────────────────────────────────────────────────

	private record Fixture(String token, LocalDate today, Map<Long, String> labelById) {
	}

	/**
	 * 키워드 토큰으로 격리한 11건. 날짜 경계(시작 당일·종료 당일·내일 개막·어제 폐막)와
	 * 미상 3종(시작만·종료만·둘 다)을 한 픽스처에 모은다. 커서는 size=2라 여러 페이지를 탄다.
	 *
	 * <p><b>미상 블록이 3건인 이유</b>: 정렬 축마다 "미상"이 뭉쳐 서는 블록이 페이지 크기(2)보다 커야
	 * <b>페이지 경계가 블록 한가운데에 떨어진다</b>. 그래야 미상 행을 가리키는 커서가 실제로 한 번 발급되고,
	 * 그 커서의 경계 처리가 틀린 구현이 빨간불이 된다. 미상이 2건이면 마지막 페이지에 딱 맞아 떨어져
	 * 그 커서가 아예 발급되지 않고, 틀린 구현도 초록불로 지나간다(실제로 그렇게 지나갔다 — 산출물 반증 M4).
	 */
	private Fixture 픽스처만든다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "기간토큰" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "기간검증관", ExhibitionRegion.SEOUL);

		Object[][] rows = {
				{ "시작당일", today, today.plusDays(10) },
				{ "종료당일", today.minusDays(10), today },
				{ "내일개막", today.plusDays(1), today.plusDays(20) },
				{ "미래개막", today.plusDays(30), today.plusDays(60) },
				{ "어제폐막", today.minusDays(20), today.minusDays(1) },
				{ "진행중", today.minusDays(10), today.plusDays(10) },
				{ "시작미상", null, today.plusDays(10) },
				{ "시작미상2", null, today.plusDays(15) },
				{ "종료미상", today.minusDays(10), null },
				{ "종료미상2", today.minusDays(15), null },
				{ "둘다미상", null, null },
		};

		Map<Long, String> labelById = new LinkedHashMap<>();
		for (Object[] row : rows) {
			Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
					"gperiod-" + SEQ.getAndIncrement(), token + " " + row[0],
					(LocalDate) row[1], (LocalDate) row[2], ExhibitionCategory.PAINTING));
			labelById.put(saved.getId(), (String) row[0]);
		}
		return new Fixture(token, today, labelById);
	}

	private ExhibitionCriteria.Search 검색(String keyword, LocalDate date, String sort) {
		return new ExhibitionCriteria.Search(keyword, null, null, null, null, date, sort, null, null, null, 2, null);
	}

	/** 해당 기준일로 조회한 결과의 라벨 집합. */
	private List<String> 라벨들(Fixture f, LocalDate date) {
		return 전페이지(검색(f.token, date, "latest")).stream().map(f.labelById::get).toList();
	}

	private List<Long> 전페이지(ExhibitionCriteria.Search base) {
		return 전페이지항목(base).stream().map(ExhibitionResult.ListItem::exhibitionId).toList();
	}

	/** 커서를 이어 받아 전 페이지를 훑는다. size=2라 여러 페이지를 탄다. */
	private List<ExhibitionResult.ListItem> 전페이지항목(ExhibitionCriteria.Search base) {
		List<ExhibitionResult.ListItem> items = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 200; page++) {
			ExhibitionCriteria.Search c = new ExhibitionCriteria.Search(base.keyword(), base.section(), base.period(),
					base.region(), base.category(), base.date(), base.sort(), base.lat(), base.lng(), cursor,
					base.size(), base.requesterId());
			ExhibitionResult.ListPage result = exhibitionFacade.search(c);
			items.addAll(result.content());
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}
		return items;
	}

	private static int Set고유(List<Long> ids) {
		return (int) ids.stream().distinct().count();
	}
}
