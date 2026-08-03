package modi.backend.interfaces.exhibition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import modi.backend.application.auth.AuthFacade;
import modi.backend.application.exhibition.ExhibitionCriteria;
import modi.backend.application.exhibition.ExhibitionFacade;
import modi.backend.application.exhibition.ExhibitionResult;

/**
 * 전시 목록·총 건수 응답의 <b>JSON 계약</b>을 고정한다(@WebMvcTest — 직렬화·라우팅 계층만 본다).
 *
 * <p>단언하는 계약은 두 가지다.
 * <ol>
 *   <li>목록 응답에 {@code totalCount}가 <b>기존 계약 그대로 실린다</b> — 응답 스펙 유지가 우선이라는 결정이다.
 *       필드 존재와 값까지 단언해, 봉투가 조용히 바뀌면 여기서 걸린다.</li>
 *   <li>{@code GET /exhibitions/count}가 {@code {count, exact}}를 준다(보조 엔드포인트) — {@code exact}는 지금 항상 true다.</li>
 * </ol>
 *
 * <p>{@code nextCursor}가 마지막 페이지에서도 살아 있는지를 함께 본다 —
 * {@code @JsonInclude(NON_NULL)}을 봉투 <b>전체</b>에 붙이는 실수를 하면 여기서 걸린다.
 */
@WebMvcTest(ExhibitionV1Controller.class)
class ExhibitionV1ControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@MockitoBean
	ExhibitionFacade exhibitionFacade;

	// 목록·count는 선택 인증(@OptionalAuthentication)이라 헤더 없이도 통과하지만, 리졸버 빈이 AuthFacade를 요구한다.
	@MockitoBean
	AuthFacade authFacade;

	@Test
	@DisplayName("전시 목록 JSON에 totalCount가 기존 계약 그대로 실린다")
	void 목록_응답에_totalCount가_실린다() throws Exception {
		given(exhibitionFacade.search(any(ExhibitionCriteria.Search.class)))
				.willReturn(new ExhibitionResult.ListPage(List.of(listItem()), "next-cursor-token", true, 243L));

		String body = mockMvc.perform(get("/api/v1/exhibitions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nextCursor").value("next-cursor-token"))
				.andExpect(jsonPath("$.data.hasNext").value(true))
				.andExpect(jsonPath("$.data.totalCount").value(243))
				.andReturn().getResponse().getContentAsString();

		JsonNode data = objectMapper.readTree(body).get("data");
		assertThat(fieldNames(data))
				.as("전시 목록 봉투 — 기존 응답 계약(content·nextCursor·hasNext·totalCount) 유지")
				.containsExactly("content", "nextCursor", "hasNext", "totalCount");
	}

	@Test
	@DisplayName("마지막 페이지여도 nextCursor 필드는 null로 남는다(NON_NULL을 봉투 전체에 붙이면 여기서 걸린다)")
	void 마지막_페이지에도_nextCursor_필드는_사라지지_않는다() throws Exception {
		given(exhibitionFacade.search(any(ExhibitionCriteria.Search.class)))
				.willReturn(new ExhibitionResult.ListPage(List.of(), null, false, 0L));

		String body = mockMvc.perform(get("/api/v1/exhibitions"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		JsonNode data = objectMapper.readTree(body).get("data");
		assertThat(fieldNames(data)).containsExactly("content", "nextCursor", "hasNext", "totalCount");
		assertThat(data.get("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("GET /exhibitions/count는 {count, exact}를 주고 exact는 항상 true다")
	void count_엔드포인트가_건수와_exact를_준다() throws Exception {
		given(exhibitionFacade.count(any(ExhibitionCriteria.Search.class)))
				.willReturn(new ExhibitionResult.Count(126L, true));

		String body = mockMvc.perform(get("/api/v1/exhibitions/count"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.result").value("SUCCESS"))
				.andExpect(jsonPath("$.data.count").value(126))
				.andExpect(jsonPath("$.data.exact").value(true))
				.andReturn().getResponse().getContentAsString();

		assertThat(fieldNames(objectMapper.readTree(body).get("data")))
				.containsExactly("count", "exact");
	}

	@Test
	@DisplayName("/count는 상세(/{exhibitionId})로 라우팅되지 않는다 — 리터럴 경로가 경로변수보다 먼저 잡힌다")
	void count_경로가_상세_경로변수에_먹히지_않는다() throws Exception {
		given(exhibitionFacade.count(any(ExhibitionCriteria.Search.class)))
				.willReturn(new ExhibitionResult.Count(0L, true));

		mockMvc.perform(get("/api/v1/exhibitions/count")).andExpect(status().isOk());

		then(exhibitionFacade).should().count(any(ExhibitionCriteria.Search.class));
		then(exhibitionFacade).should(org.mockito.Mockito.never()).getDetail(any());
	}

	@Test
	@DisplayName("count는 목록과 같은 필터 파라미터를 같은 Criteria로 옮긴다(정렬·커서·좌표는 받지 않는다)")
	void count는_목록과_같은_필터를_그대로_넘긴다() throws Exception {
		given(exhibitionFacade.count(any(ExhibitionCriteria.Search.class)))
				.willReturn(new ExhibitionResult.Count(7L, true));

		mockMvc.perform(get("/api/v1/exhibitions/count")
						.param("keyword", "모네")
						.param("section", "ending-soon")
						.param("period", "week")
						.param("region", "SEOUL,GYEONGGI")
						.param("category", "PHOTO,MEDIA")
						.param("date", "2026-06-30"))
				.andExpect(status().isOk());

		ArgumentCaptor<ExhibitionCriteria.Search> captor =
				ArgumentCaptor.forClass(ExhibitionCriteria.Search.class);
		then(exhibitionFacade).should().count(captor.capture());
		ExhibitionCriteria.Search criteria = captor.getValue();

		assertThat(criteria.keyword()).isEqualTo("모네");
		assertThat(criteria.section()).isEqualTo("ending-soon");
		assertThat(criteria.period()).isEqualTo("week");
		assertThat(criteria.region()).isEqualTo("SEOUL,GYEONGGI");
		assertThat(criteria.category()).isEqualTo("PHOTO,MEDIA");
		assertThat(criteria.date()).isEqualTo(LocalDate.of(2026, 6, 30));
		// 총 건수와 무관한 축은 채우지 않는다 — 채우면 count가 목록과 다른 조건을 탈 여지가 생긴다.
		assertThat(criteria.sort()).isNull();
		assertThat(criteria.cursor()).isNull();
		assertThat(criteria.size()).isNull();
		assertThat(criteria.lat()).isNull();
		assertThat(criteria.lng()).isNull();
	}

	@Test
	@DisplayName("count의 date 형식 오류도 목록과 같은 400 INVALID_INPUT이다")
	void count는_잘못된_날짜에_400을_준다() throws Exception {
		mockMvc.perform(get("/api/v1/exhibitions/count").param("date", "2026/06/30"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
	}

	private static ExhibitionResult.ListItem listItem() {
		return new ExhibitionResult.ListItem(51L, "CATALOG", "모네: 빛을 그리다", "https://cdn/poster.jpg",
				LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31), "예술의전당", "SEOUL", "PAINTING",
				null, 5, false, false);
	}

	/** 선언 순서 그대로의 필드 이름 목록(Jackson 3 — ObjectNode는 삽입 순서를 유지한다). */
	private static List<String> fieldNames(JsonNode node) {
		return List.copyOf(node.propertyNames());
	}
}
