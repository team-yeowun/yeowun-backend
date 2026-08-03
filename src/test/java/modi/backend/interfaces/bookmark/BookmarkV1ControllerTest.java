package modi.backend.interfaces.bookmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import modi.backend.application.auth.AuthFacade;
import modi.backend.application.bookmark.BookmarkCriteria;
import modi.backend.application.bookmark.BookmarkFacade;
import modi.backend.application.bookmark.BookmarkResult;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.auth.TokenClaims;

/**
 * 관심 전시 목록 응답의 <b>JSON 필드 집합을 고정</b>한다(@WebMvcTest — 직렬화 계층만 본다).
 *
 * <p><b>왜 지금 만드나</b>: {@code CursorResponse}는 전시·관심 전시·알림 <b>세 도메인이 공유하는 봉투</b>다.
 * 전시 조회 count 제거(STEP 2)가 이 봉투의 {@code totalCount}를 {@code long → Long +
 * @JsonInclude(NON_NULL)}로 바꾸므로, 봉투를 함께 쓰는 이 도메인은 코드를 한 줄도 안 고쳐도 <b>응답이 바뀔 수 있다</b>.
 * 이 저장소엔 관심 전시 컨트롤러 테스트가 없어 "안 깨졌다"고 말할 근거가 없었다 — 그 근거를 만든다.
 *
 * <p>고정하는 것은 두 가지다.
 * <ol>
 *   <li><b>필드 집합이 정확히 같다</b> — 빠져도 늘어나도 실패한다. {@code totalCount}가 살아 있는지를 명시 단언한다.</li>
 *   <li><b>마지막 페이지에서 {@code nextCursor}가 null로 남는다</b> — {@code NON_NULL}을 record <b>전체</b>에
 *       붙이면 여기서 {@code nextCursor}가 통째로 사라진다. 프론트가 {@code nextCursor === null}로 끝을
 *       판정 중이면 조용히 깨지므로, 그 실수를 이 테스트가 잡는다.</li>
 * </ol>
 */
@WebMvcTest(BookmarkV1Controller.class)
class BookmarkV1ControllerTest {

	private static final String BEARER = "Bearer test-access-token";

	/** 관심 전시 목록 응답 봉투의 계약 — 순서까지 이 그대로여야 한다. */
	private static final List<String> ENVELOPE_FIELDS =
			List.of("content", "nextCursor", "hasNext", "totalCount");

	/** 목록 항목 계약({@code ExhibitionDto.ListItemResponse}) — 전시 목록과 공유하는 스키마다. */
	private static final List<String> ITEM_FIELDS = List.of("exhibitionId", "type", "title", "posterUrl",
			"startDate", "endDate", "place", "region", "category", "artistSummary", "dDay", "free", "bookmarked");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@MockitoBean
	BookmarkFacade bookmarkFacade;

	// @Authentication 리졸버가 실제로 토큰을 파싱하므로 인증만 통과시키고 관심사는 직렬화에 둔다.
	@MockitoBean
	AuthFacade authFacade;

	@BeforeEach
	void setUp() {
		given(authFacade.requireAccess(anyString()))
				.willReturn(new TokenClaims(7L, "access", "kakao", "테스터", true));
	}

	@Test
	@DisplayName("관심 전시 목록 JSON은 content·nextCursor·hasNext·totalCount 네 필드 그대로다")
	void 관심_전시_목록_응답_필드_집합이_고정된다() throws Exception {
		given(bookmarkFacade.list(any(BookmarkCriteria.List.class)))
				.willReturn(new BookmarkResult.ListPage(List.of(listItem()), "next-cursor-token", true, 42L));

		String body = mockMvc.perform(get("/api/v1/users/me/bookmarks").header("Authorization", BEARER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.result").value("SUCCESS"))
				.andExpect(jsonPath("$.data.totalCount").value(42))
				.andExpect(jsonPath("$.data.nextCursor").value("next-cursor-token"))
				.andExpect(jsonPath("$.data.hasNext").value(true))
				.andReturn().getResponse().getContentAsString();

		JsonNode data = objectMapper.readTree(body).get("data");
		assertThat(fieldNames(data))
				.as("공유 봉투(CursorResponse)의 필드 집합 — 관심 전시는 totalCount를 계속 받아야 한다")
				.containsExactlyElementsOf(ENVELOPE_FIELDS);
		assertThat(fieldNames(data.get("content").get(0)))
				.as("목록 항목 스키마(전시 목록과 공유)")
				.containsExactlyElementsOf(ITEM_FIELDS);
	}

	@Test
	@DisplayName("마지막 페이지여도 nextCursor 필드는 null로 남는다(봉투 전체에 NON_NULL을 붙이면 여기서 걸린다)")
	void 마지막_페이지에도_nextCursor_필드는_사라지지_않는다() throws Exception {
		given(bookmarkFacade.list(any(BookmarkCriteria.List.class)))
				.willReturn(new BookmarkResult.ListPage(List.of(), null, false, 0L));

		String body = mockMvc.perform(get("/api/v1/users/me/bookmarks").header("Authorization", BEARER))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		JsonNode data = objectMapper.readTree(body).get("data");
		assertThat(fieldNames(data)).containsExactlyElementsOf(ENVELOPE_FIELDS);
		assertThat(data.get("nextCursor").isNull())
				.as("마지막 페이지 판정용 — 필드가 사라지는 것과 null인 것은 다르다")
				.isTrue();
		assertThat(data.get("totalCount").asLong()).isZero();
	}

	private static ExhibitionResult.ListItem listItem() {
		return new ExhibitionResult.ListItem(51L, "CATALOG", "모네: 빛을 그리다", "https://cdn/poster.jpg",
				LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31), "예술의전당", "SEOUL", "PAINTING",
				null, 5, false, true);
	}

	/** 선언 순서 그대로의 필드 이름 목록(Jackson 3 — ObjectNode는 삽입 순서를 유지한다). */
	private static List<String> fieldNames(JsonNode node) {
		return List.copyOf(node.propertyNames());
	}
}
