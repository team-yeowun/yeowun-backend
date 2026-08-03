package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 섹션이 "지금 볼 수 있는 전시"를 지키는지 검증한다(@SpringBootTest + Testcontainers-MySQL).
 *
 * <p><b>배경</b>: 섹션 필터를 걸면 {@code resolveOngoingOn}이 진행 중 조건을 빼버려서, "곧 끝나기 전에 봐야 할 전시"에
 * 아직 시작도 안 한 전시가, "무료로 볼 수 있는 전시"에 이미 끝난 전시가 노출됐다(2026-07-28 운영 실측 — free 섹션
 * 상위 5건이 전부 2026-09~11 시작 전시였다). ENDING_SOON·FREE는 진행 중 조건을 함께 걸도록 고쳤고,
 * OPENING_THIS_MONTH는 "앞으로 열릴 전시"가 목적이라 그대로 둔다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionSectionOngoingTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionFacade exhibitionFacade;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	// ── 무료 섹션 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("무료 섹션은 진행 중인 무료 전시만 노출한다 — 미래·종료 전시는 제외")
	void 무료섹션_진행중만_노출한다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		Long ongoing = 무료전시(today.minusDays(5), today.plusDays(5));
		Long future = 무료전시(today.plusDays(30), today.plusDays(40));
		Long ended = 무료전시(today.minusDays(40), today.minusDays(30));

		List<Long> ids = 섹션조회("free");

		assertThat(ids).contains(ongoing);
		assertThat(ids).doesNotContain(future, ended);
	}

	// ── 곧 끝나는 섹션 ───────────────────────────────────────────────────────

	@Test
	@DisplayName("곧 끝나는 섹션은 아직 시작하지 않은 전시를 제외한다")
	void 곧끝나는섹션_미시작_제외한다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		Long ongoing = 전시(today.minusDays(3), today.plusDays(3));
		// 종료일은 7일 창 안이지만 아직 시작하지 않았다 — "5일 후 종료"로 노출되던 케이스
		Long notStarted = 전시(today.plusDays(2), today.plusDays(4));

		List<Long> ids = 섹션조회("ending-soon");

		assertThat(ids).contains(ongoing);
		assertThat(ids).doesNotContain(notStarted);
	}

	// ── 이번 달 새로 열리는 섹션(회귀 방지) ──────────────────────────────────

	@Test
	@DisplayName("이번 달 새로 열리는 섹션은 진행 중 조건을 걸지 않는다 — 앞으로 열릴 전시가 목적")
	void 이번달섹션은_진행중조건을_걸지않는다() {
		LocalDate today = LocalDate.now(AppTime.KST);
		LocalDate firstOfMonth = today.withDayOfMonth(1);
		// 이번 달에 시작했고 이미 끝난 전시 — 기간 조건이 없어야 여전히 노출된다
		Long startedThisMonth = 전시(firstOfMonth, firstOfMonth.plusDays(1));

		List<Long> ids = 섹션조회("opening-this-month");

		assertThat(ids).contains(startedThisMonth);
	}

	// ── 픽스처 ──────────────────────────────────────────────────────────────

	private Long 전시(LocalDate start, LocalDate end) {
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "섹션검증미술관", ExhibitionRegion.SEOUL);
		Exhibition saved = exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId,
				"section-" + SEQ.getAndIncrement(), "섹션 검증 전시", start, end, ExhibitionCategory.PAINTING));
		return saved.getId();
	}

	/** 가격을 넣고 무료 판정을 전시 행에 굳힌다 — 적재 경로(ExhibitionRegistrationFacade)가 하는 일과 같다(V49). */
	private Long 무료전시(LocalDate start, LocalDate end) {
		Long id = 전시(start, end);
		exhibitionRepository.applyDetail(id, "무료", null, null, LocalDateTime.now());
		Exhibition exhibition = exhibitionRepository.findById(id).orElseThrow();
		exhibition.applyPriceJudgement("무료");
		exhibitionRepository.save(exhibition);
		return id;
	}

	/** 해당 섹션의 전 페이지를 커서로 훑어 id를 모은다(픽스처가 첫 페이지 밖으로 밀려도 검증이 성립하도록). */
	private List<Long> 섹션조회(String section) {
		List<Long> ids = new java.util.ArrayList<>();
		String cursor = null;
		for (int page = 0; page < 50; page++) {
			ExhibitionResult.ListPage result = exhibitionFacade.search(new ExhibitionCriteria.Search(
					null, section, null, null, null, null, "latest", null, null, cursor, 50, null));
			result.content().forEach(item -> ids.add(item.exhibitionId()));
			if (!result.hasNext()) {
				break;
			}
			cursor = result.nextCursor();
		}
		return ids;
	}
}
