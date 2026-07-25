package modi.backend.application.exhibition;

import modi.backend.ingestion.application.ExhibitionIngestionOrchestrator;
import modi.backend.ingestion.domain.SyncTrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;

/**
 * 외부 호출 감사({@code external_api_call_log})와 동기화 실행 기록({@code ingestion_run} — 슬림 스키마) 검증.
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
	ExhibitionIngestionOrchestrator ingestionOrchestrator;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("동기화 실행 — 슬림 집계(collected·inserted·trigger·시각)가 ingestion_run에 남는다(설계 §5-5)")
	void syncCatalog_실행기록_적재() {
		String externalId = nextId();
		given(exhibitionCatalogClient.isConfigured()).willReturn(true);
		given(exhibitionCatalogClient.fetchPage(any(), anyInt()))
				.willReturn(new CatalogPage(List.of(listItem(externalId)), 280));
		given(exhibitionCatalogClient.fetchDetail(eq(externalId)))
				.willThrow(new modi.backend.support.error.CoreException(
						modi.backend.domain.exhibition.catalog.ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "상세 없음"));
		long before = countSyncRuns();

		ingestionOrchestrator.syncCatalog(SyncTrigger.MANUAL);

		assertThat(countSyncRuns()).isEqualTo(before + 1);
		var run = latestSyncRun();
		assertThat(run.get("trigger_type")).isEqualTo("MANUAL");
		assertThat(run.get("collected")).isEqualTo(1);
		assertThat(run.get("inserted")).isEqualTo(1);
		assertThat(run.get("started_at")).isNotNull();
		assertThat(run.get("finished_at")).isNotNull();
	}

	@Test
	@DisplayName("호출 결과 0건 — collected 0으로 남는다(빈 실행도 실행 기록은 남는다)")
	void syncCatalog_호출없음_collected_0() {
		given(exhibitionCatalogClient.isConfigured()).willReturn(true);
		given(exhibitionCatalogClient.fetchPage(any(), anyInt())).willReturn(CatalogPage.none());

		ingestionOrchestrator.syncCatalog(SyncTrigger.MANUAL);

		var run = latestSyncRun();
		assertThat(run.get("collected")).isEqualTo(0);
		assertThat(run.get("inserted")).isEqualTo(0);
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
