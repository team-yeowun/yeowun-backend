package modi.backend.application.exhibition;

import modi.backend.ingestion.application.CatalogSynchronizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;

/**
 * 외부 호출 감사({@code external_api_call_log})와 동기화 실행 기록({@code ingestion_run}) 검증(이관 5단계).
 * <p>
 * 이 단계도 <b>읽기를 바꾸지 않으므로</b> 설계상 기존 테스트 전부에 보이지 않는다 — 적재가 통째로 no-op가 돼도
 * 응답도 exhibitions도 그대로다. "실제로 남았는가"를 보는 테스트가 없으면 이 단계는 검증되지 않은 채로 남는다.
 * <p>
 * 감사 기록은 어댑터(전송 계층)가 남기므로 여기서는 <b>{@link ExhibitionCatalogClient}를 목으로 두지 않고</b>
 * ingestion_run 쪽만 본다. 어댑터의 호출 기록은 {@code CultureExhibitionClientAuditTest}가 실 HTTP로 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExternalApiCallAuditTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	CatalogSynchronizer catalogSynchronizer;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("동기화 실행 — 원천이 말한 총 건수·집계가 ingestion_run에 남는다(로그로만 흘려보내던 값)")
	void syncCatalog_실행기록_적재() {
		String externalId = nextId();
		given(exhibitionCatalogClient.fetchAll(any()))
				.willReturn(new CatalogListData(List.of(listItem(externalId)), 280));
		given(exhibitionCatalogClient.fetchDetailSnapshot(eq(externalId))).willReturn(Optional.empty());
		long before = countSyncRuns();

		catalogSynchronizer.syncCatalog();

		assertThat(countSyncRuns()).isEqualTo(before + 1);
		var run = latestSyncRun();
		assertThat(run.get("total_count")).isEqualTo(280); // 원천이 말한 총 건수 — 현행은 파싱만 하고 버렸다
		assertThat(run.get("collected")).isEqualTo(1);
		assertThat(run.get("inserted")).isEqualTo(1);
		assertThat(run.get("started_at")).isNotNull();
		assertThat(run.get("finished_at")).isNotNull();
	}

	@Test
	@DisplayName("원천이 말한 총 건수는 수집 건수와 무관하게 그대로 기록된다(원천 규모 추이용)")
	void syncCatalog_총건수_기록() {
		// 원천이 "총 600건"이라는데 상한에 걸려 일부만 수집된 상황 — 절단 여부는 더 기록하지 않지만 총 건수는 남는다.
		given(exhibitionCatalogClient.fetchAll(any()))
				.willReturn(new CatalogListData(List.of(listItem(nextId())), 600));

		catalogSynchronizer.syncCatalog();

		assertThat(latestSyncRun().get("total_count")).isEqualTo(600);
		assertThat(latestSyncRun().get("collected")).isEqualTo(1);
	}

	@Test
	@DisplayName("인증키 미설정(호출 0) — total_count는 null로 남는다(0이 아니라 '모른다')")
	void syncCatalog_호출없음_totalCount_null() {
		// 0으로 적으면 "원천에 전시가 0건"이라는 거짓이 된다. 우리는 물어보지도 않았다.
		given(exhibitionCatalogClient.fetchAll(any())).willReturn(CatalogListData.none());

		catalogSynchronizer.syncCatalog();

		var run = latestSyncRun();
		assertThat(run.get("total_count")).isNull();
		assertThat(run.get("collected")).isEqualTo(0);
	}

	// ── 헬퍼 ────────────────────────────────────────────────────────────────────

	private String nextId() {
		return "AUDIT-" + SEQ.getAndIncrement();
	}

	private CatalogExhibitionData listItem(String externalId) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, "감사 기록 전시", "시립미술관", today.minusDays(1),
				today.plusDays(10), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관",
				null, null, null, "전시", "서울");
	}

	private long countSyncRuns() {
		return jdbcTemplate.queryForObject("select count(*) from ingestion_run", Long.class);
	}

	private java.util.Map<String, Object> latestSyncRun() {
		return jdbcTemplate.queryForMap("select * from ingestion_run order by id desc limit 1");
	}
}
