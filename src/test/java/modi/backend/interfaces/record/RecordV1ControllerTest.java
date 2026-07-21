package modi.backend.interfaces.record;

import modi.backend.ingestion.infra.culture.CultureDetail2Response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.application.CatalogSynchronizer;
import modi.backend.ingestion.application.enricher.GenreEnricher;
import modi.backend.ingestion.application.enricher.DraftPromoter;
import modi.backend.ingestion.application.enricher.DetailEnricher;
import modi.backend.application.exhibition.ExhibitionFacade;
import modi.backend.domain.auth.TokenProvider;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.user.User;
import modi.backend.domain.user.UserRepository;
import modi.backend.infra.record.RecordJpaRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecordV1ControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RecordJpaRepository recordRepository;

	@Autowired
	UserRepository userRepository;

	@Autowired
	TokenProvider tokenProvider;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository exhibitionPlaceRepository;

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

	// 스냅샷 독립성 e2e(Task 14)에서만 사용 — CATALOG 재동기화로 원본 전시 제목을 실제로 바꿔보기 위해 수집 포트를 목으로 둔다.
	@MockitoBean
	ExhibitionCatalogClient catalogClient;

	// 기록 API는 @Authentication 으로 실제 로그인 사용자를 받는다(X-User-Id 스텁 폐기).
	// 테스트는 유저를 저장한 뒤 그 유저의 access 토큰을 Bearer 로 보내 인증한다.
	private String bearerUser1;
	private String bearerUser2;

	// RecordService.create가 ExhibitionFacade.getDetail로 실제 전시를 조회해 스냅샷을 박제하므로
	// (14 Task 12), 기록 생성 요청에는 DB에 실재하는 전시 id를 사용해야 한다(임의의 51 고정값 대신).
	private Long exhibitionId;

	@BeforeEach
	void setUp() {
		recordRepository.deleteAll();
		User u1 = userRepository.save(User.createFromSocial("user1"));
		User u2 = userRepository.save(User.createFromSocial("user2"));
		bearerUser1 = "Bearer " + tokenProvider.issue(u1, "kakao").accessToken();
		bearerUser2 = "Bearer " + tokenProvider.issue(u2, "kakao").accessToken();

		Long placeId = modi.backend.domain.exhibition.catalog.ExhibitionTestFactory.placeId(
				exhibitionPlaceRepository, "예술의전당", null);
		Exhibition exhibition = exhibitionRepository.save(Exhibition.createCatalog(
				"RECORD-TEST-" + System.nanoTime(), "모네전", placeId, null, null, null, null, null, "기관"));
		exhibitionId = exhibition.getId();
	}

	@Test
	void create_record_and_get_detail() throws Exception {
		String response = mockMvc.perform(post("/api/v1/records")
						.header("Authorization", bearerUser1)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "exhibitionId": %d,
								  "writeMode": "DIRECT",
								  "viewedAt": "2020-06-28",
								  "content": "색이 따뜻해서 한참 서 있었다.",
								  "emotionCodes": ["강렬한", "서정적인"],
								  "media": [
								    {
								      "type": "PHOTO",
								      "url": "https://example.com/image.jpg",
								      "sortOrder": 0,
								      "sizeBytes": 1048576
								    }
								  ]
								}
								""".formatted(exhibitionId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.meta.result").value("SUCCESS"))
				.andExpect(jsonPath("$.data.recordId").exists())
				.andExpect(jsonPath("$.data.aiStatus").value("READY"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		String recordId = response.replaceAll("(?s).*\"recordId\":(\\d+).*", "$1");

		mockMvc.perform(get("/api/v1/records/{recordId}", recordId)
						.header("Authorization", bearerUser1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.exhibitionId").value(exhibitionId.intValue()))
				.andExpect(jsonPath("$.data.content").value("색이 따뜻해서 한참 서 있었다."))
				.andExpect(jsonPath("$.data.emotionCodes", hasSize(2)))
				.andExpect(jsonPath("$.data.media[0].type").value("PHOTO"))
				.andExpect(jsonPath("$.data.exhibitionTitle").value("모네전"))
				.andExpect(jsonPath("$.data.exhibitionPlace").value("예술의전당"));
	}

	@Test
	void get_visited_exhibitions_list() throws Exception {
		createRecord(bearerUser1, "모네 전시의 색감이 좋았다.", "MOVED");
		createRecord(bearerUser2, "다른 사용자의 기록.", "MOVED");

		mockMvc.perform(get("/api/v1/records/exhibitions/visited")
						.header("Authorization", bearerUser1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].exhibitionTitle").value("모네전"))
				.andExpect(jsonPath("$.data.content[0].exhibitionPlace").value("예술의전당"));
	}

	@Test
	@DisplayName("기록 스냅샷 독립성(e2e) — 재동기화는 기존 전시를 건드리지 않고(신규만 추가), 원본이 삭제돼도 다녀온 목록의 스냅샷 제목은 기록 작성 시점 값으로 유지된다")
	void 기록_스냅샷은_전시_원본_재동기화와_독립적이다() throws Exception {
		LocalDate today = LocalDate.now();
		String externalId = "CAT-SNAPSHOT-E2E-" + System.nanoTime();
		String originalTitle = "스냅샷 원본전시-" + System.nanoTime();
		String mutatedTitle = "스냅샷 변경후전시-" + System.nanoTime();

		// 1) 전시 동기화(목) — 원본 제목으로 CATALOG 최초 적재
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(listData(List.of(
				new CatalogExhibitionData(externalId, originalTitle, "스냅샷 갤러리", today.minusDays(5),
						today.plusDays(25), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
						"https://poster/snapshot.jpg", null, "기관", null, null, null, "전시", "서울"))));
		// 상세를 못 받으면 게이트를 못 채워 승격이 막힌다(Optional 제거 이후) — 이 테스트 주제와 무관하므로 최소 상세를 준다.
		given(catalogClient.fetchDetail(anyString())).willReturn(new CultureDetail2Response.Item("SEQ", null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null));
		catalogSynchronizer.syncCatalog();
		detailEnricher.enrichDetails(); // 스테이징 → 상세 해소(ADR-10 — 전시는 승격 후에만 나타난다)
		genreEnricher.enrichGenres();
		draftPromoter.promoteReady(); // 승격 소비(ADR-12) // 장르 분류(테스트 기본 mock) + 승격
		Long catalogExhibitionId = exhibitionRepository.findByExternalId(externalId).orElseThrow().getId();

		// 2) 기록 작성 — RecordService.create가 이 시점의 전시 제목을 스냅샷으로 박제한다
		String createResponse = mockMvc.perform(post("/api/v1/records")
						.header("Authorization", bearerUser1)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "exhibitionId": %d,
								  "writeMode": "DIRECT",
								  "viewedAt": "2020-06-28",
								  "content": "스냅샷 독립성 검증용 기록",
								  "emotionCodes": ["MOVED"],
								  "media": []
								}
								""".formatted(catalogExhibitionId)))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String recordId = createResponse.replaceAll("(?s).*\"recordId\":(\\d+).*", "$1");

		// 3) 작성 직후 다녀온 목록 — 스냅샷 제목이 원본 제목과 일치
		mockMvc.perform(get("/api/v1/records/exhibitions/visited")
						.header("Authorization", bearerUser1)
						.param("exhibitionId", String.valueOf(catalogExhibitionId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[?(@.recordId==" + recordId + ")].exhibitionTitle")
						.value(hasItem(originalTitle)));

		// 4) 같은 externalId를 다른 제목으로 재동기화 — 동기화는 "신규만 추가" 정책이라 기존 행을 건드리지 않는다.
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(listData(List.of(
				new CatalogExhibitionData(externalId, mutatedTitle, "스냅샷 갤러리 이전", today.minusDays(5),
						today.plusDays(25), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
						"https://poster/mutated.jpg", null, "기관", null, null, null, "전시", "서울"))));
		catalogSynchronizer.syncCatalog();
		detailEnricher.enrichDetails(); // 스테이징 → 상세 해소(ADR-10 — 전시는 승격 후에만 나타난다)
		genreEnricher.enrichGenres();
		draftPromoter.promoteReady(); // 승격 소비(ADR-12) // 장르 분류(테스트 기본 mock) + 승격

		// 기존 전시 행이 원천 갱신본으로 덮이지 않았음을 확인한다(신규만 추가 — 재적재 갱신 없음).
		Exhibition afterResync = exhibitionRepository.findByExternalId(externalId).orElseThrow();
		assertThat(afterResync.getTitle()).isEqualTo(originalTitle);

		// 5) 원본 전시가 삭제(soft-delete)돼도 — 남아 있는 유일한 원본 변경 경로 — 다녀온 목록의 스냅샷 제목은
		//    여전히 "기록 작성 시점" 제목이다(스냅샷은 기록 행에 박제되어 전시 원본과 무관).
		afterResync.delete();
		exhibitionRepository.save(afterResync);
		mockMvc.perform(get("/api/v1/records/exhibitions/visited")
						.header("Authorization", bearerUser1)
						.param("exhibitionId", String.valueOf(catalogExhibitionId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[?(@.recordId==" + recordId + ")].exhibitionTitle")
						.value(hasItem(originalTitle)))
				.andExpect(jsonPath("$.data.content[?(@.recordId==" + recordId + ")].exhibitionTitle")
						.value(not(hasItem(mutatedTitle))));
	}

	@Test
	void search_records_with_keyword_and_emotion() throws Exception {
		createRecord(bearerUser1, "모네 전시의 색감이 좋았다.", "MOVED");
		createRecord(bearerUser1, "조용한 조각 전시였다.", "CALM");
		createRecord(bearerUser2, "모네를 다른 사용자가 기록했다.", "MOVED");

		mockMvc.perform(get("/api/v1/records")
						.header("Authorization", bearerUser1)
						.param("keyword", "모네")
						.param("emotion", "MOVED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].recordId").exists())
				.andExpect(jsonPath("$.data.content[0].emotionCodes", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].emotionCodes[0]").value("MOVED"));
	}

	@Test
	void update_and_delete_record() throws Exception {
		String recordId = createRecord(bearerUser1, "처음 감상", "MOVED");

		mockMvc.perform(put("/api/v1/records/{recordId}", recordId)
						.header("Authorization", bearerUser1)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "viewedAt": "2020-06-29",
								  "content": "수정한 감상",
								  "emotionCodes": ["서정적인"],
								  "media": []
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content").value("수정한 감상"))
				.andExpect(jsonPath("$.data.emotionCodes[0]").value("서정적인"));

		mockMvc.perform(delete("/api/v1/records/{recordId}", recordId)
						.header("Authorization", bearerUser1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.result").value("SUCCESS"));

		mockMvc.perform(get("/api/v1/records/{recordId}", recordId)
						.header("Authorization", bearerUser1))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.meta.errorCode").value("NOT_FOUND"));
	}

	@Test
	void reject_future_viewed_at() throws Exception {
		mockMvc.perform(post("/api/v1/records")
						.header("Authorization", bearerUser1)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "exhibitionId": 51,
								  "writeMode": "DIRECT",
								  "viewedAt": "2999-01-01",
								  "content": "미래 관람 기록",
								  "emotionCodes": ["MOVED"],
								  "media": []
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	@Test
	void reject_video_over_100mb() throws Exception {
		mockMvc.perform(post("/api/v1/records")
						.header("Authorization", bearerUser1)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "exhibitionId": 51,
								  "writeMode": "DIRECT",
								  "viewedAt": "2020-06-28",
								  "content": "영상이 큰 기록",
								  "emotionCodes": ["MOVED"],
								  "media": [
								    {
								      "type": "VIDEO",
								      "url": "https://example.com/video.mp4",
								      "sortOrder": 0,
								      "sizeBytes": 104857601
								    }
								  ]
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_MEDIA"));
	}

	@Test
	void reject_request_without_access_token() throws Exception {
		mockMvc.perform(get("/api/v1/records"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.meta.errorCode").value("NO_ACCESS_TOKEN"));
	}

	private String createRecord(String bearer, String content, String emotion) throws Exception {
		String response = mockMvc.perform(post("/api/v1/records")
						.header("Authorization", bearer)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "exhibitionId": %d,
								  "writeMode": "DIRECT",
								  "viewedAt": "2020-06-28",
								  "content": "%s",
								  "emotionCodes": ["%s"],
								  "media": []
								}
								""".formatted(exhibitionId, content, emotion)))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return response.replaceAll("(?s).*\"recordId\":(\\d+).*", "$1");
	}

	/**
	 * 목록 수집 결과 래퍼 — 포트가 이제 "원천이 말한 총 건수·절단 여부"까지 돌려준다(이관 5단계, ingestion_run이 채울 값).
	 * 이 테스트들의 관심사가 아니라 아이템만 담고 totalCount는 수집 수와 같게 둔다(= 절단 없음).
	 */
	private static CatalogPage listData(java.util.List<CatalogExhibitionData> items) {
		return new CatalogPage(items, items.size());
	}

}
