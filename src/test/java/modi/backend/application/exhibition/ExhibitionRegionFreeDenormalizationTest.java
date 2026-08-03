package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionTestFactory;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.support.time.AppTime;

/**
 * 지역·무료 비정규화(V49)가 <b>무엇을 약속하는지</b> 고정한다.
 *
 * <p>비정규화는 "원본과 어긋날 수 있다"는 대가를 지불하고 서브쿼리와 LIKE를 없애는 거래다. 그 대가를
 * 통제 가능한 형태로 못 박는 것이 이 테스트다. 네 가지를 고정한다:
 * <ol>
 *   <li><b>필터와 표시가 같은 출처를 본다</b> — 지역으로 걸러 나온 카드에 적힌 지역이 곧 그 필터값이다.
 *       필터는 복제본, 표시는 전시장 조인이면 여기서 갈린다.</li>
 *   <li><b>복제본은 스냅샷이다</b> — 전시장 지역이 나중에 바뀌어도 이미 적재된 전시는 따라가지 않는다
 *       (사용자 결정: "그때의 전시를 그대로 저장하는 것"). 갱신 경로를 몰래 넣으면 여기서 걸린다.</li>
 *   <li><b>전시장을 재지정하면 지역도 함께 옮겨간다</b> — 스냅샷 결정과 모순되지 않는다.
 *       전시장 <i>자신의</i> 지역이 바뀌는 것과, 전시가 <i>다른 전시장</i>으로 옮겨가는 것은 다른 사건이다.</li>
 *   <li><b>무료 필터가 새 규칙을 탄다</b> — 부분 무료("… 노인 및 유아 무료")는 무료 목록에서 빠진다.
 *       옛 {@code LIKE '%무료%'}로 되돌아가면 여기서 걸린다.</li>
 * </ol>
 *
 * <p>세 경로(목록·count·표시)를 매번 함께 본다 — 셋 중 하나만 복제본을 보면 사용자에게는
 * "검색 결과 수와 실제 목록이 다르다" 또는 "서울로 걸렀는데 경기라고 적혀 있다"로 나타난다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionRegionFreeDenormalizationTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionFacade exhibitionFacade;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;
	/** 수집→코어 승격의 유일한 통로. 픽스처 대신 이걸 불러야 복제본을 굳히는 코드가 검증 대상이 된다. */
	@Autowired
	ExhibitionRegistrar exhibitionRegistrar;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("지역으로 거르면 목록·count가 일치하고, 카드에 적힌 지역이 곧 그 필터값이다")
	void 지역_필터와_표시가_같은_출처를_본다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "지역출처토큰" + SEQ.getAndIncrement();
		전시(token + " 서울전시 1", ExhibitionRegion.SEOUL, today);
		전시(token + " 서울전시 2", ExhibitionRegion.SEOUL, today);
		전시(token + " 부산전시", ExhibitionRegion.BUSAN, today);

		ExhibitionResult.ListPage page = exhibitionFacade.search(검색(token, "SEOUL", null));
		ExhibitionResult.Count count = exhibitionFacade.count(검색(token, "SEOUL", null));

		assertThat(page.content()).hasSize(2);
		assertThat(count.count()).as("목록과 count가 같은 술어를 본다").isEqualTo(2);
		assertThat(page.content()).extracting(ExhibitionResult.ListItem::region)
				.as("필터가 SEOUL이면 표시도 SEOUL이어야 한다 — 여기가 갈리면 화면과 검색이 어긋난다")
				.containsOnly("SEOUL");
	}

	@Test
	@DisplayName("적재 뒤 전시장 지역이 바뀌어도 이미 적재된 전시의 지역은 그대로다(스냅샷)")
	void 복제본은_갱신되지_않는_스냅샷이다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "스냅샷토큰" + SEQ.getAndIncrement();
		String placeName = "스냅샷검증관" + SEQ.getAndIncrement();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, placeName, ExhibitionRegion.JEJU);
		exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, ExhibitionRegion.JEJU,
				"snap-" + SEQ.getAndIncrement(), token + " 전시", today.minusDays(1), today.plusDays(30),
				ExhibitionCategory.PAINTING));

		// 전시장 자신의 지역을 바꾼다(운영에서 데이터를 손보는 상황).
		ExhibitionPlace place = exhibitionPlaceRepository.findById(placeId).orElseThrow();
		org.springframework.test.util.ReflectionTestUtils.setField(place, "region", ExhibitionRegion.GANGWON);
		exhibitionPlaceRepository.save(place);

		assertThat(exhibitionFacade.count(검색(token, "JEJU", null)).count())
				.as("복제본은 갱신하지 않는다 — 적재 시점의 지역으로 계속 잡힌다").isEqualTo(1);
		assertThat(exhibitionFacade.count(검색(token, "GANGWON", null)).count())
				.as("전시장의 새 지역으로는 잡히지 않는다").isZero();
		assertThat(exhibitionFacade.search(검색(token, "JEJU", null)).content())
				.extracting(ExhibitionResult.ListItem::region)
				.as("표시도 같은 스냅샷을 본다(전시장 조인이면 GANGWON이 나왔을 것)")
				.containsExactly("JEJU");
	}

	@Test
	@DisplayName("전시를 다른 전시장으로 재지정하면 지역 스냅샷도 함께 옮겨간다")
	void 전시장_재지정은_지역도_옮긴다() {
		Long from = ExhibitionTestFactory.placeId(exhibitionPlaceRepository,
				"재지정출발관" + SEQ.getAndIncrement(), ExhibitionRegion.SEOUL);
		Long to = ExhibitionTestFactory.placeId(exhibitionPlaceRepository,
				"재지정도착관" + SEQ.getAndIncrement(), ExhibitionRegion.BUSAN);
		Exhibition exhibition = exhibitionRepository.save(ExhibitionTestFactory.catalog(from,
				ExhibitionRegion.SEOUL, "move-" + SEQ.getAndIncrement(), "재지정 전시",
				LocalDate.now(AppTime.KST), LocalDate.now(AppTime.KST).plusDays(10), ExhibitionCategory.PAINTING));

		ExhibitionPlace target = exhibitionPlaceRepository.findById(to).orElseThrow();
		exhibition.reassignPlace(target.getId(), target.getRegion());
		exhibitionRepository.save(exhibition);

		Exhibition reloaded = exhibitionRepository.findById(exhibition.getId()).orElseThrow();
		assertThat(reloaded.getExhibitionPlaceId()).isEqualTo(to);
		assertThat(reloaded.getRegion()).as("전시장이 옮겨가면 지역 스냅샷도 따라간다").isEqualTo(ExhibitionRegion.BUSAN);
	}

	@Test
	@DisplayName("무료 필터는 굳은 판정을 보고, 부분 무료(성인 유료+노인 무료)는 새 규칙대로 빠진다")
	void 무료_필터는_새_규칙을_탄다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "무료규칙토큰" + SEQ.getAndIncrement();
		Long 무료 = 전시(token + " 완전무료", ExhibitionRegion.SEOUL, today);
		Long 부분무료 = 전시(token + " 부분무료", ExhibitionRegion.SEOUL, today);
		Long 유료 = 전시(token + " 유료", ExhibitionRegion.SEOUL, today);
		가격(무료, "무료 *단체관람은 홈페이지 신청 필수");
		가격(부분무료, "성인 2,000원 / 청소년 1,000원 / 어린이 500원 / 노인 및 유아 무료");
		가격(유료, "성인 5,000원");

		ExhibitionResult.ListPage page = exhibitionFacade.search(검색(token, null, "free"));
		ExhibitionResult.Count count = exhibitionFacade.count(검색(token, null, "free"));

		assertThat(page.content()).extracting(ExhibitionResult.ListItem::exhibitionId)
				.as("옛 규칙(LIKE '%무료%')이었다면 부분무료도 들어왔다")
				.containsExactly(무료);
		assertThat(count.count()).as("목록과 count가 같은 술어를 본다").isEqualTo(1);
		assertThat(page.content()).extracting(ExhibitionResult.ListItem::free).containsOnly(true);

		// 무료 필터 없이 보면 셋 다 나오되, free 배지는 굳은 판정을 그대로 비춘다.
		assertThat(exhibitionFacade.search(검색(token, null, null)).content())
				.filteredOn(item -> item.free()).extracting(ExhibitionResult.ListItem::exhibitionId)
				.containsExactly(무료);
	}

	/**
	 * <b>승격 경로가 복제본을 실제로 굳히는지</b> 고정한다 — 위 네 테스트가 못 잡는 구멍이다.
	 *
	 * <p>위 테스트들의 픽스처({@code 전시()}·{@code 가격()})는 적재 경로가 하는 일을 <b>테스트가 손으로 재현</b>한다.
	 * 그래서 {@link modi.backend.application.exhibition.contract.ExhibitionRegistrationFacade}가
	 * {@code applyPriceJudgement}·{@code place.getRegion()} 복제를 <b>빼먹어도 전부 통과한다</b> —
	 * 테스트가 대신 채워주기 때문이다. 그 경우 수집이 등록하는 모든 전시가 {@code is_free=false}·
	 * {@code region=null}로 남아 <b>무료 전시가 무료 필터에 안 나오고 지역 필터에서 사라지는데</b>
	 * 어떤 테스트도 빨간불이 되지 않는다. 조용히 망가지는 종류다.
	 *
	 * <p>그래서 이 테스트는 픽스처를 쓰지 않고 <b>실제 승격 계약({@link ExhibitionRegistrar#register})을 직접 호출</b>한다.
	 * 복제를 굳히는 코드가 사라지면 여기서 빨간불이 뜬다.
	 */
	@Test
	@DisplayName("승격 경로가 지역·무료 복제본을 전시 행에 굳힌다(수집이 등록한 무료 전시가 무료 필터에 나온다)")
	void 승격_경로가_복제본을_굳힌다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "승격복제토큰" + SEQ.getAndIncrement();

		long 무료 = 승격(token + " 승격 완전무료", ExhibitionRegion.SEOUL, "무료", today).exhibitionId();
		long 부분무료 = 승격(token + " 승격 부분무료", ExhibitionRegion.SEOUL,
				"성인 2,000원 / 청소년 1,000원 / 노인 및 유아 무료", today).exhibitionId();
		long 가격미상 = 승격(token + " 승격 가격미상", ExhibitionRegion.SEOUL, null, today).exhibitionId();

		// 1) 전시 행에 굳었는가 — applyPriceJudgement + save가 사라지면 셋 다 false가 된다.
		assertThat(exhibitionRepository.findById(무료).orElseThrow().isFree())
				.as("승격이 무료 판정을 전시 행에 굳혀야 한다 — 안 굳으면 무료 전시가 무료 필터에서 사라진다").isTrue();
		assertThat(exhibitionRepository.findById(부분무료).orElseThrow().isFree())
				.as("부분 무료는 새 규칙대로 유료다").isFalse();
		assertThat(exhibitionRepository.findById(가격미상).orElseThrow().isFree())
				.as("가격 미상은 무료가 아니다(옛 규칙과 같다)").isFalse();

		// 2) 지역 스냅샷도 승격이 굳히는가 — place.getRegion() 복제가 빠지면 null이 되어 지역 필터에서 사라진다.
		assertThat(exhibitionRepository.findById(무료).orElseThrow().getRegion())
				.as("승격이 방금 resolve한 전시장의 지역을 전시 행에 복제해야 한다").isEqualTo(ExhibitionRegion.SEOUL);

		// 3) 사용자에게 보이는 경로로도 확인한다 — 굳은 값이 필터·표시에 실제로 쓰인다.
		ExhibitionResult.ListPage 무료목록 = exhibitionFacade.search(검색(token, null, "free"));
		assertThat(무료목록.content()).extracting(ExhibitionResult.ListItem::exhibitionId)
				.as("승격된 무료 전시가 무료 필터에 나와야 한다").containsExactly(무료);
		assertThat(exhibitionFacade.count(검색(token, null, "free")).count())
				.as("목록과 count가 같은 술어를 본다").isEqualTo(1);
		assertThat(exhibitionFacade.count(검색(token, "SEOUL", null)).count())
				.as("승격된 전시 셋 다 지역 필터에 잡혀야 한다").isEqualTo(3);
	}

	// ── 픽스처 ──────────────────────────────────────────────────────────────

	/**
	 * <b>실제 승격 계약</b>으로 전시 한 건을 등록한다. 위 {@code 전시()}·{@code 가격()}과 달리 복제본을
	 * 테스트가 채우지 않는다 — 채우는 것은 프로덕션 코드의 책임이고, 그것이 이 픽스처로 검증하려는 대상이다.
	 */
	private ExhibitionRegistrar.Registered 승격(String title, ExhibitionRegion region, String price, LocalDate today) {
		String externalId = "promote-" + SEQ.getAndIncrement();
		ExhibitionRegistration registration = new ExhibitionRegistration(externalId, title,
				"승격검증관-" + region.name(), region, null, null, null, today.minusDays(1), today.plusDays(30),
				ExhibitionCategory.PAINTING, null, null, "테스트", price, null, null, null, null, null,
				"회화", GenreProvider.MOCK, null);
		return exhibitionRegistrar.register(registration, LocalDateTime.now());
	}

	/** 지역이 같은 전시장 + 그 지역을 복제한 전시. 실제 적재 경로가 하는 일과 같다(전시장 resolve → 지역 복제). */
	private Long 전시(String title, ExhibitionRegion region, LocalDate today) {
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository,
				"비정규화검증관-" + region.name(), region);
		return exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, region,
				"denorm-" + SEQ.getAndIncrement(), title, today.minusDays(1), today.plusDays(30),
				ExhibitionCategory.PAINTING)).getId();
	}

	/** 상세 가격을 채우고, 적재 경로와 같은 방식으로 무료 판정을 전시 행에 굳힌다. */
	private void 가격(Long exhibitionId, String price) {
		exhibitionRepository.applyDetail(exhibitionId, price, null, null, LocalDateTime.now());
		Exhibition exhibition = exhibitionRepository.findById(exhibitionId).orElseThrow();
		exhibition.applyPriceJudgement(price);
		exhibitionRepository.save(exhibition);
	}

	private ExhibitionCriteria.Search 검색(String keyword, String region, String section) {
		return new ExhibitionCriteria.Search(keyword, section, null, region, null, null, "latest", null, null,
				null, 20, null);
	}
}
