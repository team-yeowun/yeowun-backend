package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestion.application.CatalogSynchronizer;
import modi.backend.ingestion.properties.CatalogFetchProperties;
import modi.backend.ingestion.application.ExhibitionSyncFacade;
import modi.backend.ingestion.application.draft.ExhibitionDraftFacade;
import modi.backend.application.exhibition.contract.DetailTargetState;
import modi.backend.application.exhibition.contract.ExhibitionBackfill;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * syncCatalog 라우팅 정책 단위 검증(ADR-10) — 루프는 <b>목록 외 외부 호출 0</b>: 완성 전시=스킵,
 * 레거시 미완성=FETCH_DETAIL 메시지로 뒤채움 위임(인라인 상세 조회 없음), 그 외=draft 스테이징.
 * 기간 비정상 원천은 스킵하되 벤더 원본은 남긴다.
 */
class ExhibitionCatalogSyncTest {

	private ExhibitionSyncFacade facade;
	private ExhibitionDraftFacade draftFacade;
	private ExhibitionOutboxFacade outboxFacade;
	private ExhibitionBackfill backfill;
	private ExhibitionCatalogClient catalogClient;
	private CatalogSynchronizer synchronizer;

	@BeforeEach
	void setUp() {
		facade = mock(ExhibitionSyncFacade.class);
		draftFacade = mock(ExhibitionDraftFacade.class);
		outboxFacade = mock(ExhibitionOutboxFacade.class);
		backfill = mock(ExhibitionBackfill.class);
		catalogClient = mock(ExhibitionCatalogClient.class);
		synchronizer = new CatalogSynchronizer(facade, backfill, draftFacade, outboxFacade, catalogClient,
				new CatalogFetchProperties(null, null, null, null, null));
	}

	private static CatalogListData listData(List<CatalogExhibitionData> items) {
		return new CatalogListData(items, items.size(), false);
	}

	private static CatalogExhibitionData data(String externalId, String title) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, title, "장소", today.minusDays(1), today.plusDays(10),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관", null, null, null, "전시", "서울",
				null);
	}

	@Test
	@DisplayName("syncCatalog — 신규는 draft로 스테이징하고, 상세를 인라인으로 조회하지 않는다(목록 외 외부 호출 0)")
	void syncCatalog_신규_스테이징() {
		given(catalogClient.fetchAll(any())).willReturn(listData(List.of(data("CAT-NEW", "신규 전시"))));
		given(backfill.findDetailTargetState("CAT-NEW")).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isEqualTo(1);
		verify(draftFacade).stageFromList(any(), any()); // FETCH_DETAIL enqueue는 스테이징 트랜잭션 안(파사드)에서
		verify(catalogClient, never()).fetchDetailSnapshot(any()); // 상세는 아웃박스 릴레이가 조회한다
	}

	@Test
	@DisplayName("syncCatalog — 이미 완성된 전시는 스테이징도 위임도 없이 건너뛴다")
	void syncCatalog_완성전시_스킵() {
		given(catalogClient.fetchAll(any())).willReturn(listData(List.of(data("CAT-DONE", "완성 전시"))));
		given(backfill.findDetailTargetState("CAT-DONE")).willReturn(DetailTargetState.ALREADY_SYNCED);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isZero();
		verify(draftFacade, never()).stageFromList(any(), any());
		verify(outboxFacade, never()).enqueue(any(), any(), any());
	}

	@Test
	@DisplayName("syncCatalog — 레거시 미완성 전시는 FETCH_DETAIL 메시지로 뒤채움을 위임한다(인라인 조회 없음)")
	void syncCatalog_레거시미완성_메시지위임() {
		given(catalogClient.fetchAll(any())).willReturn(listData(List.of(data("CAT-OLD", "기존 전시"))));
		given(backfill.findDetailTargetState("CAT-OLD")).willReturn(DetailTargetState.NEEDS_DETAIL);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isZero();
		verify(outboxFacade).enqueue(eq(OutboxMessageType.FETCH_DETAIL), eq("CAT-OLD"), any());
		verify(catalogClient, never()).fetchDetailSnapshot(any());
		verify(draftFacade, never()).stageFromList(any(), any()); // 이미 승격된 행 — draft를 만들지 않는다
	}

	@Test
	@DisplayName("syncCatalog — 기간 비정상(종료<시작) 원천은 스킵하되 벤더 원본은 남긴다")
	void syncCatalog_기간비정상_스킵() {
		LocalDate today = LocalDate.now();
		CatalogExhibitionData invalid = new CatalogExhibitionData("CAT-BAD", "역전 기간", "장소", today,
				today.minusDays(1), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관",
				null, null, null, "전시", "서울", null);
		given(catalogClient.fetchAll(any())).willReturn(listData(List.of(invalid, data("CAT-OK", "정상 전시"))));
		given(backfill.findDetailTargetState("CAT-OK")).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isEqualTo(1); // CAT-OK만 스테이징
		verify(facade).archiveListSnapshot(eq(invalid), any()); // 탈락 항목도 원본은 보존(요구사항 명문화)
		verify(draftFacade, never()).stageFromList(eq(invalid), any());
	}

	@Test
	@DisplayName("syncCatalog — 재sync에서 미종료 draft는 목록분 갱신(REFRESHED)으로 집계된다")
	void syncCatalog_재sync_갱신집계() {
		given(catalogClient.fetchAll(any())).willReturn(listData(List.of(data("CAT-RE", "재동기화 전시"))));
		given(backfill.findDetailTargetState("CAT-RE")).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.REFRESHED);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isZero(); // 갱신은 신규 스테이징 수에 잡히지 않는다
		verify(facade).archiveIngestionRun(any(), eq(0), eq(1), eq(0), eq(0));
	}
}
