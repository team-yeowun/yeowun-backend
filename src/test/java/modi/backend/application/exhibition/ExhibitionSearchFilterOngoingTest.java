package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * 탐색 필터가 "진행 중"을 지키는지, 그리고 <b>검색만은 예외</b>인지 검증한다(@SpringBootTest + Testcontainers-MySQL).
 *
 * <p><b>배경</b>: {@code resolveOngoingOn}이 "필터가 하나라도 있으면 기간 조건 제거"로 뭉뚱그려져 있어, 지역·카테고리를
 * 고르면 아직 열지 않은 전시가 목록 상단을 독식했다(2026-07-28 운영 실측 — {@code region=SEOUL} 상위 5건이 전부
 * 9~12월 시작). 필터는 <b>목록을 좁히는 도구</b>지 다른 목록으로 바꾸는 도구가 아니므로 기본 목록과 같은 기간을 유지한다.
 *
 * <p>검색만 예외다. 검색은 "지금 목록 좁히기"가 아니라 <b>"아는 것 찾기"</b>라, 뉴스에서 본 다음 달 전시나 지난달에 본
 * 전시를 이름으로 찾을 수 있어야 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionSearchFilterOngoingTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionFacade exhibitionFacade;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("지역 필터는 진행 중인 전시만 노출한다 — 미래·종료 전시는 제외")
	void 지역필터_진행중만_노출한다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		Long placeId = 전시장("제주필터검증관", ExhibitionRegion.JEJU);
		Long ongoing = 전시(placeId, "제주 진행 전시", today.minusDays(3), today.plusDays(3), ExhibitionCategory.PAINTING);
		Long future = 전시(placeId, "제주 미래 전시", today.plusDays(40), today.plusDays(50), ExhibitionCategory.PAINTING);
		Long ended = 전시(placeId, "제주 종료 전시", today.minusDays(50), today.minusDays(40), ExhibitionCategory.PAINTING);

		List<Long> ids = 조회(null, "JEJU", null);

		assertThat(ids).contains(ongoing);
		assertThat(ids).doesNotContain(future, ended);
	}

	@Test
	@DisplayName("카테고리 필터는 진행 중인 전시만 노출한다 — 미래·종료 전시는 제외")
	void 카테고리필터_진행중만_노출한다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		Long placeId = 전시장("조각필터검증관", ExhibitionRegion.ULSAN);
		Long ongoing = 전시(placeId, "조각 진행 전시", today.minusDays(2), today.plusDays(2), ExhibitionCategory.SCULPTURE);
		Long future = 전시(placeId, "조각 미래 전시", today.plusDays(60), today.plusDays(70), ExhibitionCategory.SCULPTURE);
		Long ended = 전시(placeId, "조각 종료 전시", today.minusDays(70), today.minusDays(60), ExhibitionCategory.SCULPTURE);

		List<Long> ids = 조회(null, null, "SCULPTURE");

		assertThat(ids).contains(ongoing);
		assertThat(ids).doesNotContain(future, ended);
	}

	@Test
	@DisplayName("검색은 아직 열지 않은 전시도 찾지만, 이미 끝난 전시는 제외한다")
	void 검색은_미래는_찾고_종료는_제외한다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "검색토큰" + SEQ.getAndIncrement();
		Long placeId = 전시장("검색필터검증관", ExhibitionRegion.GANGWON);
		Long ongoing = 전시(placeId, token + " 진행", today.minusDays(1), today.plusDays(1), ExhibitionCategory.PAINTING);
		Long future = 전시(placeId, token + " 미래", today.plusDays(90), today.plusDays(100), ExhibitionCategory.PAINTING);
		Long ended = 전시(placeId, token + " 종료", today.minusDays(100), today.minusDays(90), ExhibitionCategory.PAINTING);

		List<Long> ids = 조회(token, null, null);

		// 다음 달 개막 전시는 이름으로 찾을 수 있어야 하고(미래 포함), 이미 끝난 전시는 보여줄 이유가 없다.
		assertThat(ids).contains(ongoing, future);
		assertThat(ids).doesNotContain(ended);
	}

	@Test
	@DisplayName("종료일이 오늘인 전시는 검색에 남는다 — 경계는 '오늘까지 관람 가능'")
	void 검색_종료일이_오늘이면_포함한다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		String token = "경계토큰" + SEQ.getAndIncrement();
		Long placeId = 전시장("경계필터검증관", ExhibitionRegion.SEJONG);
		Long endsToday = 전시(placeId, token + " 오늘종료", today.minusDays(10), today, ExhibitionCategory.PAINTING);
		Long endedYesterday = 전시(placeId, token + " 어제종료", today.minusDays(10), today.minusDays(1),
				ExhibitionCategory.PAINTING);

		List<Long> ids = 조회(token, null, null);

		assertThat(ids).contains(endsToday);
		assertThat(ids).doesNotContain(endedYesterday);
	}

	// ── 픽스처 ──────────────────────────────────────────────────────────────

	private Long 전시장(String name, ExhibitionRegion region) {
		return ExhibitionTestFactory.placeId(exhibitionPlaceRepository, name, region);
	}

	/**
	 * 전시장의 지역을 전시 행에도 복제한다 — 적재 경로가 하는 일과 같다(V49). 이걸 빠뜨리면 지역 필터가
	 * 0건을 돌려주고, "포함 없음 · 제외 없음"이라 지역 테스트가 <b>공허하게 통과</b>한다.
	 */
	private Long 전시(Long placeId, String title, LocalDate start, LocalDate end, ExhibitionCategory category) {
		ExhibitionRegion region = exhibitionPlaceRepository.findById(placeId).orElseThrow().getRegion();
		Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, region,
				"filter-" + SEQ.getAndIncrement(), title, start, end, category));
		return saved.getId();
	}

	/** 전 페이지를 커서로 훑어 id를 모은다(픽스처가 첫 페이지 밖으로 밀려도 검증이 성립하도록). */
	private List<Long> 조회(String keyword, String region, String category) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 50; page++) {
			ExhibitionResult.ListPage result = exhibitionFacade.search(new ExhibitionCriteria.Search(
					keyword, null, null, region, category, null, "latest", null, null, cursor, 50, null));
			result.content().forEach(item -> ids.add(item.exhibitionId()));
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}
		return ids;
	}
}
