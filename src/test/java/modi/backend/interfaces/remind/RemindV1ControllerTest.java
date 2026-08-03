package modi.backend.interfaces.remind;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.auth.TokenProvider;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.record.AiStatus;
import modi.backend.domain.record.ExhibitionSnapshot;
import modi.backend.domain.record.Record;
import modi.backend.domain.record.RecordEmotion;
import modi.backend.domain.record.WriteMode;
import modi.backend.domain.user.User;
import modi.backend.domain.user.UserRepository;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.infra.remind.RemindJpaRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RemindV1ControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RemindJpaRepository remindRepository;

	@Autowired
	RecordJpaRepository recordRepository;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Autowired
	modi.backend.domain.exhibition.catalog.ArtistRepository artistRepository;

	@Autowired
	modi.backend.infra.exhibition.catalog.ExhibitionArtistJpaRepository exhibitionArtistRepository;

	@Autowired
	UserRepository userRepository;

	@Autowired
	TokenProvider tokenProvider;

	@Autowired
	JdbcTemplate jdbcTemplate;

	private String bearerUser1;
	private String bearerUser2;
	private Long recordId;

	@BeforeEach
	void setUp() {
		remindRepository.deleteAll();
		recordRepository.deleteAll();
		User u1 = userRepository.save(User.createFromSocial("remind-user1"));
		User u2 = userRepository.save(User.createFromSocial("remind-user2"));
		bearerUser1 = "Bearer " + tokenProvider.issue(u1, "kakao").accessToken();
		bearerUser2 = "Bearer " + tokenProvider.issue(u2, "kakao").accessToken();

		Long placeId = modi.backend.domain.exhibition.catalog.ExhibitionTestFactory.placeId(
				exhibitionPlaceRepository, "동작아트갤러리", null);
		Exhibition exhibition = exhibitionRepository.save(Exhibition.createCustom(
				u1.getId(), "조용한 호숫가", placeId, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
				null, null, "김미경 외 10인", "http://poster/lake.jpg"));
		// 작가는 조인(N:M)에서 조립되므로 명시적으로 연결한다(리마인드 응답 artist 조립 경로 검증). resolve-or-create로 UK 충돌 방지.
		String artistName = modi.backend.domain.exhibition.catalog.Artist.normalize("김미경 외 10인");
		modi.backend.domain.exhibition.catalog.Artist artist = artistRepository.findByName(artistName)
				.orElseGet(() -> artistRepository.save(modi.backend.domain.exhibition.catalog.Artist.create(artistName)));
		if (!exhibitionArtistRepository.existsByExhibitionIdAndArtistId(exhibition.getId(), artist.getId())) {
			exhibitionArtistRepository.save(
					modi.backend.domain.exhibition.catalog.ExhibitionArtist.of(exhibition.getId(), artist.getId()));
		}

		Record record = Record.create(u1.getId(), exhibition.getId(),
				new ExhibitionSnapshot("조용한 호숫가", "CUSTOM", "http://poster/lake.jpg", "동작아트갤러리",
						"SEOUL", "PAINTING", null, null),
				WriteMode.DIRECT, LocalDate.of(2026, 7, 3), "빛이 천천히 번지는 전시실을 지났다.", null, null, null,
				AiStatus.READY);
		record.replaceEmotions(List.of(RecordEmotion.create("평화로운"), RecordEmotion.create("차분한")));
		recordId = recordRepository.save(record).getId();
	}

	@Test
	@DisplayName("리마인드 저장 → 상세 → 목록: before(그때)·after(지금)·AI 상태를 반환한다")
	void save_get_list() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reminds")
						.header("Authorization", bearerUser1)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "recordId": %d,
								  "emotionCodes": ["슬픔", "서정적인"],
								  "reflection": "당시엔 강렬했는데 다시 보니 슬픔이 더 다가온다"
								}
								""".formatted(recordId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.result").value("SUCCESS"))
				.andExpect(jsonPath("$.data.remindId").exists())
				.andExpect(jsonPath("$.data.recordId").value(recordId.intValue()))
				.andExpect(jsonPath("$.data.before.text").value("빛이 천천히 번지는 전시실을 지났다."))
				.andExpect(jsonPath("$.data.before.emotionCodes", hasSize(2)))
				.andExpect(jsonPath("$.data.after.text").value("당시엔 강렬했는데 다시 보니 슬픔이 더 다가온다"))
				.andExpect(jsonPath("$.data.after.emotionCodes", hasSize(2)))
				.andExpect(jsonPath("$.data.aiStatus").value("SKIPPED")) // 테스트 환경엔 AI 키 없음 → best-effort 스킵
				.andReturn().getResponse().getContentAsString();
		String remindId = response.replaceAll("(?s).*\"remindId\":(\\d+).*", "$1");

		mockMvc.perform(get("/api/v1/reminds/{remindId}", remindId)
						.header("Authorization", bearerUser1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.before.text").value("빛이 천천히 번지는 전시실을 지났다."))
				.andExpect(jsonPath("$.data.after.emotionCodes[0]").value("슬픔"));

		mockMvc.perform(get("/api/v1/reminds")
						.header("Authorization", bearerUser1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].exhibitionTitle").value("조용한 호숫가"))
				// 감정 변화(유지/반전) 필터용 — 원본(그때)·회고(지금) 감정을 함께 내린다
				.andExpect(jsonPath("$.data.content[0].beforeEmotionCodes", hasSize(2)))
				.andExpect(jsonPath("$.data.content[0].emotionCodes", hasSize(2)));
	}

	@Test
	@DisplayName("소환 — 7일 이상 지난 미회고 기록을 반환(경과 라벨·당시 감정 포함)")
	void candidate_backdated() throws Exception {
		// 기록 작성 시각을 10일 전으로 소급(소환 조건: 7일 이상 경과)
		jdbcTemplate.update("update records set created_at = ? where id = ?",
				Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusDays(10)), recordId);

		mockMvc.perform(get("/api/v1/reminds/candidate")
						.header("Authorization", bearerUser1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.recordId").value(recordId.intValue()))
				.andExpect(jsonPath("$.data.elapsedLabel").value("1주일 전"))
				.andExpect(jsonPath("$.data.artist").value("김미경 외 10인"))
				.andExpect(jsonPath("$.data.originalEmotionCodes", hasSize(2)));
	}

	@Test
	@DisplayName("소환 — 최근(7일 미만) 기록만 있으면 대상 없음")
	void candidate_none_when_recent() throws Exception {
		mockMvc.perform(get("/api/v1/reminds/candidate")
						.header("Authorization", bearerUser1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.recordId").doesNotExist());
	}

	@Test
	@DisplayName("소환 — 이미 회고한 기록은 소환 대상에서 제외")
	void candidate_excludes_reminded() throws Exception {
		jdbcTemplate.update("update records set created_at = ? where id = ?",
				Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusDays(10)), recordId);
		mockMvc.perform(post("/api/v1/reminds")
						.header("Authorization", bearerUser1)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recordId\": %d, \"reflection\": \"한 번 회고함\"}".formatted(recordId)))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/reminds/candidate")
						.header("Authorization", bearerUser1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.recordId").doesNotExist());
	}

	@Test
	@DisplayName("타인 기록엔 회고 불가 — 403 FORBIDDEN")
	void forbidden_other_user_record() throws Exception {
		mockMvc.perform(post("/api/v1/reminds")
						.header("Authorization", bearerUser2)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recordId\": %d, \"reflection\": \"남의 기록\"}".formatted(recordId)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("소감이 비면 400")
	void reject_blank_reflection() throws Exception {
		mockMvc.perform(post("/api/v1/reminds")
						.header("Authorization", bearerUser1)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recordId\": %d, \"reflection\": \"  \"}".formatted(recordId)))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("미인증 요청은 401")
	void reject_without_token() throws Exception {
		mockMvc.perform(get("/api/v1/reminds"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.meta.errorCode").value("NO_ACCESS_TOKEN"));
	}
}
