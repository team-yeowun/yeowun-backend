package modi.backend.interfaces;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.application.CatalogSynchronizer;
import modi.backend.ingestion.application.enricher.GenreEnricher;
import modi.backend.ingestion.application.enricher.DraftPromoter;
import modi.backend.ingestion.application.enricher.DetailEnricher;
import modi.backend.application.exhibition.ExhibitionFacade;
import modi.backend.domain.bookmark.ExhibitionBookmarkRepository;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.DetailFetch;
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.infra.auth.KakaoApi;

/**
 * 전시 도메인(03_전시.md) API end-to-end 검증(커서 페이지네이션 CursorResponse).
 * 외부 두 경계만 목으로 둔다: 공공데이터 수집 포트({@link ExhibitionCatalogClient})와 카카오 로그인 HTTP({@link KakaoApi}).
 * 나머지(컨트롤러·Facade·Entity·DB Testcontainers)는 실제로 태운다. CATALOG는 syncCatalog 또는 리포지토리로 적재해 조회한다.
 * DB는 메서드 간 공유되므로 신규 표본은 고유 keyword로 격리해 단언한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExhibitionIntegrationTest {

	private static final String MONET = "모네: 빛의 정원";
	private static final String PICASSO = "피카소 회고전";
	private static final String PHOTO_SHOW = "서울 골목 사진전";
	private static final String REDIRECT_URI = "http://localhost:3000/login";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ExhibitionFacade exhibitionFacade;

	@Autowired
	CatalogSynchronizer catalogSynchronizer;

	@Autowired
	DetailEnricher detailEnricher;

	@Autowired
	GenreEnricher genreEnricher;

	@Autowired
	DraftPromoter draftPromoter;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Autowired
	modi.backend.infra.exhibition.catalog.ExhibitionDetailJpaRepository exhibitionDetailRepository;

	@Autowired
	ExhibitionBookmarkRepository exhibitionBookmarkRepository;

	@MockitoBean
	ExhibitionCatalogClient catalogClient;

	@MockitoBean
	KakaoApi kakaoApi;

	/** 고정 CATALOG 표본을 목 수집 포트로 주입해 upsert 적재한다(멱등 — 재실행해도 동일 집합). */
	@BeforeEach
	void seedCatalog() {
		LocalDate today = LocalDate.now();
		given(catalogClient.fetchAll(any())).willReturn(listData(List.of(
				new CatalogExhibitionData("CAT-MONET", MONET, "예술의전당", today.minusDays(10), today.plusDays(30),
						ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, "https://poster/monet.jpg",
						"https://culture.go.kr/monet", "한국문화정보원", 126.980781, 37.578608,
						"종로구", "전시", "서울", null),
				new CatalogExhibitionData("CAT-PICASSO", PICASSO, "시립미술관", today.minusDays(100),
						today.minusDays(50), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null,
						"기관", null, null,
						null, "전시", "서울", null),
				new CatalogExhibitionData("CAT-PHOTO", PHOTO_SHOW, "성수 갤러리", today.minusDays(5),
						today.plusDays(15), ExhibitionRegion.SEOUL, ExhibitionCategory.PHOTO, null, null,
						"기관", null, null,
						null, "사진", "서울", null))));
		// syncCatalog가 적재 시점에 상세2까지 함께 채운다 — CAT-MONET만 상세를 준다(나머지는 상세 없음 → 목록 필드만).
		given(catalogClient.fetchDetailSnapshot("CAT-MONET")).willReturn(Optional.of(new DetailFetch(
				new CatalogDetailData("성인 20,000원", "모네 특별전 설명", "https://detail/monet", "02-1234-5678",
						"https://img/monet.jpg", "https://place/monet", "서울 어딘가", "PLACE-SEQ-1"), null)));
		catalogSynchronizer.syncCatalog();
		detailEnricher.enrichDetails(); // 스테이징 → 상세 해소(ADR-10 — 전시는 승격 후에만 나타난다)
		genreEnricher.enrichGenres();
		draftPromoter.promoteReady(); // 승격 소비(ADR-12) // 장르 분류(테스트 기본 mock) + 승격
	}

	/**
	 * 목록 수집 결과 래퍼 — 포트가 이제 "원천이 말한 총 건수·절단 여부"까지 돌려준다(이관 5단계, ingestion_run이 채울 값).
	 * 이 테스트들의 관심사가 아니라 아이템만 담고 totalCount는 수집 수와 같게 둔다(= 절단 없음).
	 */
	private static CatalogListData listData(java.util.List<CatalogExhibitionData> items) {
		return new CatalogListData(items, items.size(), false);
	}

	/** 표본 CATALOG를 리포지토리로 직접 적재(가격·좌표·기간 제어). 기본 startDate는 과거로 둬 최신순 상단을 침범하지 않게 한다. */
	private Long saveCatalog(String externalId, String title, LocalDate startDate, LocalDate endDate,
			ExhibitionRegion region, ExhibitionCategory category, String price, Double gpsX, Double gpsY) {
		// 전시장은 전시마다 고유(region·gps 필터·거리순이 전시별로 갈리게) — 자연키 이름을 externalId로 유일하게.
		Long placeId = exhibitionPlaceRepository.save(
				modi.backend.domain.exhibition.catalog.ExhibitionPlace.createFromList(title + "@" + externalId, region, null,
						gpsX, gpsY)).getId();
		Exhibition e = exhibitionRepository.save(
				Exhibition.createCatalog(externalId, title, placeId, startDate, endDate, category, null, null, "기관"));
		if (price != null && !price.isBlank()) {
			exhibitionDetailRepository.save(modi.backend.domain.exhibition.catalog.ExhibitionDetail.create(
					e.getId(), price, null, null, java.time.LocalDateTime.now()));
		}
		return e.getId();
	}

	private String loginAndGetAccessToken(long providerUserId, String nickname) throws Exception {
		given(kakaoApi.getToken(any())).willReturn(Map.of("access_token", "kakao-access-token"));
		given(kakaoApi.getUserInfo(anyString())).willReturn(Map.of(
				"id", providerUserId,
				"kakao_account", Map.of("profile", Map.of("nickname", nickname))));
		MvcResult login = mockMvc.perform(post("/api/v1/auth/login/kakao")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"auth-code\",\"redirectUri\":\"" + REDIRECT_URI + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");
	}

	private long userIdOf(String accessToken) throws Exception {
		MvcResult me = mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andReturn();
		return ((Number) JsonPath.read(me.getResponse().getContentAsString(), "$.data.userId")).longValue();
	}

	@Test
	@DisplayName("GET /exhibitions — 필터 없음(비로그인), 200 + 커서 shape + 신규 필드(dDay/free/bookmarked) + 오늘 진행 중만")
	void 목록_기본_진행중_커서shape() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.result").value("SUCCESS"))
				.andExpect(jsonPath("$.data.content").isArray())
				.andExpect(jsonPath("$.data.hasNext").isBoolean())
				.andExpect(jsonPath("$.data.totalCount").isNumber())
				.andExpect(jsonPath("$.data.content[*].title", hasItem(MONET)))
				.andExpect(jsonPath("$.data.content[*].title", hasItem(PHOTO_SHOW)))
				// 종료된 전시(피카소)는 진행 중이 아니므로 제외
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(PICASSO))))
				// 신규 필드: 비로그인이라 bookmarked 전부 false, free는 boolean
				.andExpect(jsonPath("$.data.content[*].bookmarked", not(hasItem(true))))
				.andExpect(jsonPath("$.data.content[?(@.title=='" + MONET + "')].free").value(hasItem(false)))
				// dDay는 KST 기준 오늘로부터 종료일(약 +30일)까지 — 타임존 경계로 ±1 가능하므로 범위로 단언
				.andExpect(jsonPath("$.data.content[?(@.title=='" + MONET + "')].dDay")
						.value(hasItem(greaterThanOrEqualTo(28))));
	}

	@Test
	@DisplayName("GET /exhibitions?keyword=한글자 — 1글자 검색어, 400 INVALID_INPUT")
	void 목록_키워드_1글자_400() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions").param("keyword", "네"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("GET /exhibitions/region-groups — 필터 칩용 지역 그룹 목록(공개)")
	void 지역_그룹_조회() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions/region-groups"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.groups[0].code").value("SEOUL"))
				.andExpect(jsonPath("$.data.groups[1].code").value("GYEONGGI_INCHEON"))
				.andExpect(jsonPath("$.data.groups[1].label").value("경기·인천"))
				.andExpect(jsonPath("$.data.groups[1].regions[0]").value("GYEONGGI"))
				.andExpect(jsonPath("$.data.groups[1].regions[1]").value("INCHEON"))
				.andExpect(jsonPath("$.data.groups.length()").value(9));
	}

	@Test
	@DisplayName("GET /exhibitions?region=SEOUL,GYEONGGI — 콤마 다중 지역 필터(BUSAN 제외)")
	void 목록_다중지역_필터() throws Exception {
		LocalDate today = LocalDate.now();
		String kw = "지역다중표본";
		saveCatalog("MR-SEOUL", kw + " 서울", today.minusDays(100), today.plusDays(30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, null);
		saveCatalog("MR-GG", kw + " 경기", today.minusDays(100), today.plusDays(30),
				ExhibitionRegion.GYEONGGI, ExhibitionCategory.PAINTING, null, null, null);
		saveCatalog("MR-BUSAN", kw + " 부산", today.minusDays(100), today.plusDays(30),
				ExhibitionRegion.BUSAN, ExhibitionCategory.PAINTING, null, null, null);

		mockMvc.perform(get("/api/v1/exhibitions").param("keyword", kw).param("region", "SEOUL,GYEONGGI"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[*].title", hasItem(kw + " 서울")))
				.andExpect(jsonPath("$.data.content[*].title", hasItem(kw + " 경기")))
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(kw + " 부산"))));
	}

	@Test
	@DisplayName("GET /exhibitions?section=ending-soon — 종료 임박(오늘~+7일)만, 먼 종료는 제외")
	void 목록_섹션_ending_soon() throws Exception {
		LocalDate today = LocalDate.now();
		String kw = "종료임박표본";
		saveCatalog("ES-SOON", kw + " 곧끝남", today.minusDays(10), today.plusDays(3),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, null);
		saveCatalog("ES-FAR", kw + " 여유", today.minusDays(10), today.plusDays(30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, null);

		mockMvc.perform(get("/api/v1/exhibitions").param("keyword", kw).param("section", "ending-soon"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[*].title", hasItem(kw + " 곧끝남")))
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(kw + " 여유"))));
	}

	@Test
	@DisplayName("GET /exhibitions?section=free — 무료 전시만(유료 제외) + free=true")
	void 목록_섹션_free() throws Exception {
		LocalDate today = LocalDate.now();
		String kw = "무료섹션표본";
		saveCatalog("FR-FREE", kw + " 무료전", today.minusDays(100), today.plusDays(30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, "무료", null, null);
		saveCatalog("FR-PAID", kw + " 유료전", today.minusDays(100), today.plusDays(30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, "성인 20,000원", null, null);

		mockMvc.perform(get("/api/v1/exhibitions").param("keyword", kw).param("section", "free"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[*].title", hasItem(kw + " 무료전")))
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(kw + " 유료전"))))
				.andExpect(jsonPath("$.data.content[?(@.title=='" + kw + " 무료전')].free").value(hasItem(true)));
	}

	@Test
	@DisplayName("GET /exhibitions — 커서 페이지네이션(size=2로 2페이지, 중복·누락 없음, 끝 페이지 nextCursor=null)")
	void 목록_커서_페이징() throws Exception {
		LocalDate today = LocalDate.now();
		String kw = "커서페이징표본";
		// startDate 내림차순(최신순)으로 c1 > c2 > c3. region은 GYEONGGI로 둬 SEOUL 정렬 테스트를 침범하지 않게 한다.
		saveCatalog("CP-1", kw + " A", today.minusDays(1), today.plusDays(30),
				ExhibitionRegion.GYEONGGI, ExhibitionCategory.PAINTING, null, null, null);
		saveCatalog("CP-2", kw + " B", today.minusDays(2), today.plusDays(30),
				ExhibitionRegion.GYEONGGI, ExhibitionCategory.PAINTING, null, null, null);
		saveCatalog("CP-3", kw + " C", today.minusDays(3), today.plusDays(30),
				ExhibitionRegion.GYEONGGI, ExhibitionCategory.PAINTING, null, null, null);

		MvcResult page1 = mockMvc.perform(get("/api/v1/exhibitions")
						.param("keyword", kw).param("sort", "latest").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(2)))
				.andExpect(jsonPath("$.data.hasNext").value(true))
				.andExpect(jsonPath("$.data.totalCount").value(3))
				.andReturn();
		String body1 = page1.getResponse().getContentAsString();
		List<Integer> ids1 = JsonPath.read(body1, "$.data.content[*].exhibitionId");
		String nextCursor = JsonPath.read(body1, "$.data.nextCursor");

		MvcResult page2 = mockMvc.perform(get("/api/v1/exhibitions")
						.param("keyword", kw).param("sort", "latest").param("size", "2")
						.param("cursor", nextCursor))
				.andExpect(status().isOk())
				.andReturn();
		List<Integer> ids2 = JsonPath.read(page2.getResponse().getContentAsString(), "$.data.content[*].exhibitionId");

		// 중복 없음 + 합쳐서 3건(누락 없음)
		org.assertj.core.api.Assertions.assertThat(ids1).doesNotContainAnyElementsOf(ids2);
		org.assertj.core.api.Assertions.assertThat(ids1.size() + ids2.size()).isEqualTo(3);
	}

	@Test
	@DisplayName("GET /exhibitions?cursor=손상 — 잘못된 커서, 400 INVALID_CURSOR")
	void 목록_손상된_커서_400() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions").param("sort", "latest").param("cursor", "!!!corrupt!!!"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_CURSOR"));
	}

	@Test
	@DisplayName("GET /exhibitions?sort=distance (좌표 없음) — 400 INVALID_INPUT")
	void 목록_거리순_좌표없음_400() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions").param("sort", "distance"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("GET /exhibitions?sort=distance&lat&lng — 좌표 기준 가까운 순(가장 가까운 표본이 1위)")
	void 목록_거리순_정렬() throws Exception {
		LocalDate today = LocalDate.now();
		String kw = "거리표본";
		saveCatalog("DIST-NEAR", kw + " 가까움", today.minusDays(100), today.plusDays(30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, 127.00, 37.50);
		saveCatalog("DIST-MID", kw + " 중간", today.minusDays(100), today.plusDays(30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, 127.50, 37.50);
		saveCatalog("DIST-FAR", kw + " 멈", today.minusDays(100), today.plusDays(30),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, 128.00, 37.50);

		mockMvc.perform(get("/api/v1/exhibitions")
						.param("keyword", kw).param("sort", "distance")
						.param("lat", "37.50").param("lng", "127.00"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].title").value(kw + " 가까움"))
				.andExpect(jsonPath("$.data.totalCount").value(3));
	}

	@Test
	@DisplayName("GET /exhibitions?date=과거 — 그 날짜 진행 중이던 전시(피카소)만")
	void 목록_과거날짜_진행중() throws Exception {
		String pastDate = LocalDate.now().minusDays(70).toString();
		mockMvc.perform(get("/api/v1/exhibitions").param("date", pastDate))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[*].title", hasItem(PICASSO)))
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(MONET))));
	}

	@Test
	@DisplayName("GET /exhibitions?region=SEOUL&category=PHOTO — 카테고리 필터(사진전만, 회화 제외)")
	void 목록_카테고리_필터() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions").param("region", "SEOUL").param("category", "PHOTO"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[*].title", hasItem(PHOTO_SHOW)))
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(MONET))));
	}

	@Test
	@DisplayName("GET /exhibitions?keyword=모네 — 전시명 부분 일치")
	void 목록_키워드_검색() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions").param("keyword", "모네"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[*].title", hasItem(MONET)))
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(PHOTO_SHOW))));
	}

	@Test
	@DisplayName("GET /exhibitions?region=SEOUL&sort=latest — 시작일 최신순(시작일이 가장 늦은 사진전이 1위)")
	void 목록_정렬_latest() throws Exception {
		// region 필터를 함께 걸어 ongoing 기본 제한을 해제 — 종료된 피카소전도 정렬 대상에 포함시켜 비교한다.
		mockMvc.perform(get("/api/v1/exhibitions").param("region", "SEOUL").param("sort", "latest"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].title").value(PHOTO_SHOW));
	}

	@Test
	@DisplayName("GET /exhibitions?region=SEOUL&sort=popular — 조회수순(상세를 여러 번 조회한 피카소전이 1위)")
	void 목록_정렬_popular() throws Exception {
		Long picassoId = exhibitionRepository.findByExternalId("CAT-PICASSO").orElseThrow().getId();
		// 상세 GET은 매 호출마다 ourViewCount를 1씩 증가시킨다 — 5회 조회해 다른 전시보다 조회수를 확실히 앞세운다.
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/v1/exhibitions/{id}", picassoId)).andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/v1/exhibitions").param("region", "SEOUL").param("sort", "popular"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].title").value(PICASSO));
	}

	@Test
	@DisplayName("GET /exhibitions?region=SEOUL&sort=ending — 종료일 임박순(이미 종료된 피카소전이 1위)")
	void 목록_정렬_ending() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions").param("region", "SEOUL").param("sort", "ending"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].title").value(PICASSO));
	}

	@Test
	@DisplayName("GET /exhibitions/banners — 진행 중 CATALOG만(종료 전시 제외), 조회수 상위순, 최대 3개, 배너이미지=포스터")
	void 배너_진행중_조회수순() throws Exception {
		// 진행 중인 사진전 상세를 여러 번 조회해 조회수를 확실히 올림 → 배너 1위가 되게(다른 테스트의 부수 조회수를 넘어서도록 넉넉히).
		Long photoId = exhibitionRepository.findByExternalId("CAT-PHOTO").orElseThrow().getId();
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(get("/api/v1/exhibitions/{id}", photoId)).andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/v1/exhibitions/banners"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.result").value("SUCCESS"))
				.andExpect(jsonPath("$.data.banners").isArray())
				.andExpect(jsonPath("$.data.banners.length()", lessThanOrEqualTo(3)))
				// 진행 중(모네·사진전) 포함, 종료된 피카소는 제외
				.andExpect(jsonPath("$.data.banners[*].title", hasItem(PHOTO_SHOW)))
				.andExpect(jsonPath("$.data.banners[*].title", hasItem(MONET)))
				.andExpect(jsonPath("$.data.banners[*].title", not(hasItem(PICASSO))))
				// 조회수를 크게 올린 사진전이 1위
				.andExpect(jsonPath("$.data.banners[0].title").value(PHOTO_SHOW))
				// 배너 이미지는 전시 포스터(모네는 posterUrl 보유)
				.andExpect(jsonPath("$.data.banners[?(@.title=='" + MONET + "')].bannerImageUrl")
						.value(hasItem("https://poster/monet.jpg")));
	}

	@Test
	@DisplayName("GET /exhibitions?region=NOPE — 잘못된 enum, 400 INVALID_INPUT")
	void 목록_잘못된_enum_400() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions").param("region", "NOPE"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("GET /exhibitions?date=abc — 잘못된 날짜 포맷, 400 INVALID_INPUT")
	void 목록_잘못된_날짜_400() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions").param("date", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("GET /exhibitions/{id} — 존재하는 CATALOG, 비로그인 200 + 신규 필드(free/bookmarked/recorded/artistSummary)")
	void 상세_카탈로그_공개_신규필드() throws Exception {
		Long id = exhibitionRepository.findByExternalId("CAT-MONET").orElseThrow().getId();
		mockMvc.perform(get("/api/v1/exhibitions/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.exhibitionId").value(id))
				.andExpect(jsonPath("$.data.type").value("CATALOG"))
				.andExpect(jsonPath("$.data.title").value(MONET))
				.andExpect(jsonPath("$.data.artists").isArray())
				.andExpect(jsonPath("$.data.artists").isEmpty())
				.andExpect(jsonPath("$.data.keywords").isArray())
				// 승격 게이트에 장르가 필수라(ADR-10) CATALOG는 이제 항상 분류돼 나타난다(테스트 기본 mock — 결정적).
				.andExpect(jsonPath("$.data.keywords").isNotEmpty())
				// CATALOG의 artistSummary는 null, 비로그인이라 bookmarked·recorded false
				.andExpect(jsonPath("$.data.artistSummary").doesNotExist())
				.andExpect(jsonPath("$.data.free").isBoolean())
				.andExpect(jsonPath("$.data.bookmarked").value(false))
				.andExpect(jsonPath("$.data.recorded").value(false));
	}

	@Test
	@DisplayName("GET /exhibitions/{id} — 로그인+관심 등록 시 bookmarked=true, 무료 전시는 free=true")
	void 상세_관심등록_bookmarked_true() throws Exception {
		String token = loginAndGetAccessToken(7200001L, "관심유저");
		long userId = userIdOf(token);
		Long freeId = saveCatalog("BM-FREE", "관심표본 무료전", LocalDate.now().minusDays(100),
				LocalDate.now().plusDays(30), ExhibitionRegion.SEJONG, ExhibitionCategory.PAINTING, "무료", null, null);
		exhibitionBookmarkRepository.add(userId, freeId);

		mockMvc.perform(get("/api/v1/exhibitions/{id}", freeId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.free").value(true))
				.andExpect(jsonPath("$.data.bookmarked").value(true))
				.andExpect(jsonPath("$.data.recorded").value(false));
	}

	@Test
	@DisplayName("GET /exhibitions/{id} — 동기화 시 채운 상세 필드 노출, 내부 보존 필드는 비노출")
	void 상세_지연수집_필드_노출() throws Exception {
		// CAT-MONET은 seedCatalog의 syncCatalog에서 이미 상세2까지 채워진 완전한 행 — 상세 엔드포인트가 그 필드를 노출한다.
		Long id = exhibitionRepository.findByExternalId("CAT-MONET").orElseThrow().getId();

		mockMvc.perform(get("/api/v1/exhibitions/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.address").value("서울 어딘가"))
				.andExpect(jsonPath("$.data.imgUrl").value("https://img/monet.jpg"))
				.andExpect(jsonPath("$.data.phone").value("02-1234-5678"))
				.andExpect(jsonPath("$.data.viewCount").value(greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.data.sigungu").value("종로구"))
				.andExpect(jsonPath("$.data.placeUrl").value("https://place/monet"))
				.andExpect(jsonPath("$.data.realmName").doesNotExist())
				.andExpect(jsonPath("$.data.areaText").doesNotExist())
				.andExpect(jsonPath("$.data.placeSeq").doesNotExist());
	}

	@Test
	@DisplayName("GET /exhibitions/{id} — 존재하지 않는 전시, 404 NOT_FOUND")
	void 상세_없음_404() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions/{id}", 99999999L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.meta.errorCode").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("POST /exhibitions/custom — 로그인+제목만, 200 + exhibitionId·type=CUSTOM")
	void 개인전시_등록_정상() throws Exception {
		String token = loginAndGetAccessToken(7000001L, "등록유저");
		mockMvc.perform(post("/api/v1/exhibitions/custom")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"동네 골목 사진전\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.exhibitionId").isNumber())
				.andExpect(jsonPath("$.data.type").value("CUSTOM"));
	}

	@Test
	@DisplayName("POST /exhibitions/custom — 전시 형태·작가 포함 → 상세에 format=SOLO·artists=[작가]·artistSummary=작가 반영")
	void 개인전시_등록_전시형태_작가() throws Exception {
		String token = loginAndGetAccessToken(7000010L, "형태유저");
		MvcResult created = mockMvc.perform(post("/api/v1/exhibitions/custom")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "title": "조용한 오후",
								  "place": "아리랑 문화관",
								  "startDate": "2026-06-24",
								  "endDate": "2026-07-31",
								  "region": "SEOUL",
								  "format": "SOLO",
								  "artist": "김선영"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.type").value("CUSTOM"))
				.andReturn();
		long customId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.data.exhibitionId"))
				.longValue();

		mockMvc.perform(get("/api/v1/exhibitions/{id}", customId)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.format").value("SOLO"))
				.andExpect(jsonPath("$.data.place").value("아리랑 문화관"))
				.andExpect(jsonPath("$.data.artists", hasSize(1)))
				.andExpect(jsonPath("$.data.artists[0]").value("김선영"))
				.andExpect(jsonPath("$.data.artistSummary").value("김선영"));
	}

	@Test
	@DisplayName("POST /exhibitions/custom — 정의되지 않은 전시 형태, 400 INVALID_INPUT")
	void 개인전시_등록_잘못된_형태_400() throws Exception {
		String token = loginAndGetAccessToken(7000011L, "형태오류유저");
		mockMvc.perform(post("/api/v1/exhibitions/custom")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"형태오류전\",\"format\":\"UNKNOWN\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("POST /exhibitions/custom — 비로그인, 401")
	void 개인전시_등록_미인증_401() throws Exception {
		mockMvc.perform(post("/api/v1/exhibitions/custom")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"무단 등록\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("POST /exhibitions/custom — 제목 누락, 400 INVALID_INPUT")
	void 개인전시_등록_제목누락_400() throws Exception {
		String token = loginAndGetAccessToken(7000002L, "제목유저");
		mockMvc.perform(post("/api/v1/exhibitions/custom")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"place\":\"성수동\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("POST /exhibitions/custom — 종료일<시작일, 400 INVALID_INPUT")
	void 개인전시_등록_기간역전_400() throws Exception {
		String token = loginAndGetAccessToken(7000003L, "기간유저");
		mockMvc.perform(post("/api/v1/exhibitions/custom")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"기간 역전전\",\"startDate\":\"2026-06-30\",\"endDate\":\"2026-06-20\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("CUSTOM 노출/접근 — 개인 전시는 탐색 목록에 아무에게도(본인 포함) 안 보이고, 상세는 본인만 200·타인/비로그인 403")
	void 개인전시_노출_권한() throws Exception {
		String ownerToken = loginAndGetAccessToken(7100001L, "소유자");
		String otherToken = loginAndGetAccessToken(7100002L, "타인");
		String uniqueTitle = "소유자만 보는 개인전-7100001";

		MvcResult created = mockMvc.perform(post("/api/v1/exhibitions/custom")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"" + uniqueTitle + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		long customId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.data.exhibitionId"))
				.longValue();

		// 목록(전시탐색): 개인 전시는 등록자 본인 목록에도, 비로그인/타인 목록에도 노출되지 않는다.
		mockMvc.perform(get("/api/v1/exhibitions").param("keyword", uniqueTitle)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(uniqueTitle))));
		mockMvc.perform(get("/api/v1/exhibitions").param("keyword", uniqueTitle))
				.andExpect(jsonPath("$.data.content[*].title", not(hasItem(uniqueTitle))));

		// 상세: 본인 200, 타인 403, 비로그인 403
		mockMvc.perform(get("/api/v1/exhibitions/{id}", customId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.type").value("CUSTOM"));
		mockMvc.perform(get("/api/v1/exhibitions/{id}", customId)
						.header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
		mockMvc.perform(get("/api/v1/exhibitions/{id}", customId))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
	}
}
