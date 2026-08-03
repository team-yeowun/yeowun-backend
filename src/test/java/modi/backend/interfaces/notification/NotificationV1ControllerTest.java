package modi.backend.interfaces.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
import modi.backend.application.notification.NotificationCriteria;
import modi.backend.application.notification.NotificationFacade;
import modi.backend.application.notification.NotificationResult;
import modi.backend.domain.auth.TokenClaims;

/**
 * 알림 목록 응답의 <b>JSON 필드 집합을 고정</b>한다(@WebMvcTest — 직렬화 계층만 본다).
 *
 * <p>이유는 {@code BookmarkV1ControllerTest}와 같다 — {@code CursorResponse}는 전시·관심 전시·알림이
 * 공유하는 봉투라, 전시 쪽 count 제거가 <b>이 도메인의 코드를 안 건드리고도</b> 응답을 바꿀 수 있다.
 * 알림은 이번 성능 실험이 측정조차 하지 않은 도메인이므로 "안전할 것"이라고 쓸 근거가 더더욱 없다.
 */
@WebMvcTest(NotificationV1Controller.class)
class NotificationV1ControllerTest {

	private static final String BEARER = "Bearer test-access-token";

	/** 알림 목록 응답 봉투의 계약 — 순서까지 이 그대로여야 한다. */
	private static final List<String> ENVELOPE_FIELDS =
			List.of("content", "nextCursor", "hasNext", "totalCount");

	/** 알림 항목 계약({@code NotificationDto.NotificationItem}). */
	private static final List<String> ITEM_FIELDS = List.of("notificationId", "type", "title", "body",
			"targetId", "imageUrl", "read", "createdAt");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@MockitoBean
	NotificationFacade notificationFacade;

	@MockitoBean
	AuthFacade authFacade;

	@BeforeEach
	void setUp() {
		given(authFacade.requireAccess(anyString()))
				.willReturn(new TokenClaims(7L, "access", "kakao", "테스터", true));
	}

	@Test
	@DisplayName("알림 목록 JSON은 content·nextCursor·hasNext·totalCount 네 필드 그대로다")
	void 알림_목록_응답_필드_집합이_고정된다() throws Exception {
		given(notificationFacade.getNotifications(any(NotificationCriteria.List.class)))
				.willReturn(new NotificationResult.List(List.of(item()), "next-cursor-token", true, 13L));

		String body = mockMvc.perform(get("/api/v1/notifications").header("Authorization", BEARER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.result").value("SUCCESS"))
				.andExpect(jsonPath("$.data.totalCount").value(13))
				.andExpect(jsonPath("$.data.nextCursor").value("next-cursor-token"))
				.andExpect(jsonPath("$.data.hasNext").value(true))
				.andReturn().getResponse().getContentAsString();

		JsonNode data = objectMapper.readTree(body).get("data");
		assertThat(fieldNames(data))
				.as("공유 봉투(CursorResponse)의 필드 집합 — 알림은 totalCount를 계속 받아야 한다")
				.containsExactlyElementsOf(ENVELOPE_FIELDS);
		assertThat(fieldNames(data.get("content").get(0)))
				.as("알림 항목 스키마")
				.containsExactlyElementsOf(ITEM_FIELDS);
	}

	@Test
	@DisplayName("마지막 페이지여도 nextCursor 필드는 null로 남는다(봉투 전체에 NON_NULL을 붙이면 여기서 걸린다)")
	void 마지막_페이지에도_nextCursor_필드는_사라지지_않는다() throws Exception {
		given(notificationFacade.getNotifications(any(NotificationCriteria.List.class)))
				.willReturn(new NotificationResult.List(List.of(), null, false, 0L));

		String body = mockMvc.perform(get("/api/v1/notifications").header("Authorization", BEARER))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		JsonNode data = objectMapper.readTree(body).get("data");
		assertThat(fieldNames(data)).containsExactlyElementsOf(ENVELOPE_FIELDS);
		assertThat(data.get("nextCursor").isNull())
				.as("마지막 페이지 판정용 — 필드가 사라지는 것과 null인 것은 다르다")
				.isTrue();
		assertThat(data.get("totalCount").asLong()).isZero();
	}

	private static NotificationResult.Item item() {
		return new NotificationResult.Item(9L, "EXHIBITION", "곧 종료되는 전시가 있어요", "모네전이 3일 뒤 종료됩니다",
				51L, "https://cdn/poster.jpg", false,
				ZonedDateTime.of(2026, 7, 29, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
	}

	/** 선언 순서 그대로의 필드 이름 목록(Jackson 3 — ObjectNode는 삽입 순서를 유지한다). */
	private static List<String> fieldNames(JsonNode node) {
		return List.copyOf(node.propertyNames());
	}
}
