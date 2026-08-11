package modi.backend.application.exhibition.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;

import modi.backend.TestcontainersConfiguration;
import modi.backend.application.exhibition.ExhibitionResult;

import tools.jackson.databind.ObjectMapper;

/**
 * - L2(Redis)에 담기는 값이 왕복하는지 검증
 *   - 캐시 선언이 들고 다니는 valueType에 직렬화기를 바인딩하는 구조라, 그 바인딩이 실제로 도는지 봐야 함
 *   - 왕복이 깨지면 조회 실패는 {@code CacheManager}가 삼키고 DB로 폴백함
 *   - 즉 L2가 영영 안 먹는데도 겉으로는 멀쩡해 보임 — 테스트 없이는 드러나지 않는 종류
 *
 * - {@code LocalDate}·중첩 record·null 필드가 관문
 *   - 목록 항목은 시작일·종료일이 {@code LocalDate}
 *   - {@code ListPage}는 {@code List<ListItem>}을 품은 중첩 구조
 *   - {@code artistSummary}는 목록에서 항상 null
 *
 * - 운영과 같은 {@code ObjectMapper}를 주입받아 씀
 *   - 테스트에서 새로 만들면 운영 설정(JavaTime 등)과 어긋나 검증이 무의미해짐
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionCacheSerializationTest {

	@Autowired
	private ObjectMapper objectMapper;

	private <T> T 왕복(T value, Class<T> type) {
		JacksonJsonRedisSerializer<T> serializer = new JacksonJsonRedisSerializer<>(objectMapper, type);
		return serializer.deserialize(serializer.serialize(value));
	}

	@Test
	@DisplayName("목록 페이지가 Redis 직렬화를 왕복해도 그대로다")
	void listPage_왕복() {
		ExhibitionResult.ListPage page = new ExhibitionResult.ListPage(
				List.of(new ExhibitionResult.ListItem(1L, "CATALOG", "전시", "poster",
						LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30), "전시장", "SEOUL", "ART",
						null, 12, true, false)),
				"cursor-abc", true, 126L);

		assertThat(왕복(page, ExhibitionResult.ListPage.class)).isEqualTo(page);
	}

	@Test
	@DisplayName("배너 목록이 Redis 직렬화를 왕복해도 그대로다")
	void banners_왕복() {
		ExhibitionResult.Banners banners = new ExhibitionResult.Banners(
				List.of(new ExhibitionResult.Banner(1L, "전시", "banner-url",
						LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30), "전시장")));

		assertThat(왕복(banners, ExhibitionResult.Banners.class)).isEqualTo(banners);
	}

	@Test
	@DisplayName("빈 페이지·커서 없음도 왕복한다 — 마지막 페이지의 모양")
	void listPage_빈페이지_왕복() {
		ExhibitionResult.ListPage page = new ExhibitionResult.ListPage(List.of(), null, false, 0L);

		assertThat(왕복(page, ExhibitionResult.ListPage.class)).isEqualTo(page);
	}

	@Test
	@DisplayName("날짜는 타임스탬프 숫자가 아니라 ISO 문자열로 실린다")
	void 날짜_ISO문자열() {
		ExhibitionResult.Banners banners = new ExhibitionResult.Banners(
				List.of(new ExhibitionResult.Banner(1L, "전시", "banner-url",
						LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30), "전시장")));

		JacksonJsonRedisSerializer<ExhibitionResult.Banners> serializer =
				new JacksonJsonRedisSerializer<>(objectMapper, ExhibitionResult.Banners.class);

		assertThat(new String(serializer.serialize(banners))).contains("2026-03-01");
	}
}
