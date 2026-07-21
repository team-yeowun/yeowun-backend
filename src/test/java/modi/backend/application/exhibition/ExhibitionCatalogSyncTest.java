package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.ExternalApiOutcome;
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
		given(catalogClient.isConfigured()).willReturn(true);
	}

	private static CatalogPage listData(List<CatalogExhibitionData> items) {
		return new CatalogPage(items, items.size());
	}

	/** pageSize만큼 꽉 찬 페이지 — 순회가 다음 페이지로 넘어가는 조건이다. */
	private static CatalogPage fullPage(String... ids) {
		return new CatalogPage(java.util.Arrays.stream(ids).map(id -> data(id, "전시 " + id)).toList(), 999);
	}

	/** 페이지 크기를 바꿔 순회 조건을 만든다(기본값은 100이라 한 페이지로 끝난다). */
	private CatalogSynchronizer withPageSize(int pageSize) {
		return new CatalogSynchronizer(facade, backfill, draftFacade, outboxFacade, catalogClient,
				new CatalogFetchProperties(null, null, null, pageSize, pageSize * 5));
	}

	private static CatalogExhibitionData data(String externalId, String title) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, title, "장소", today.minusDays(1), today.plusDays(10),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관", null, null, null, "전시", "서울");
	}

	@Test
	@DisplayName("syncCatalog — 신규는 draft로 스테이징하고, 상세를 인라인으로 조회하지 않는다(목록 외 외부 호출 0)")
	void syncCatalog_신규_스테이징() {
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(listData(List.of(data("CAT-NEW", "신규 전시"))));
		given(backfill.findDetailTargetState("CAT-NEW")).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isEqualTo(1);
		verify(draftFacade).stageFromList(any(), any()); // FETCH_DETAIL enqueue는 스테이징 트랜잭션 안(파사드)에서
		verify(catalogClient, never()).fetchDetail(any()); // 상세는 아웃박스 릴레이가 조회한다
	}

	@Test
	@DisplayName("syncCatalog — 이미 완성된 전시는 스테이징도 위임도 없이 건너뛴다")
	void syncCatalog_완성전시_스킵() {
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(listData(List.of(data("CAT-DONE", "완성 전시"))));
		given(backfill.findDetailTargetState("CAT-DONE")).willReturn(DetailTargetState.ALREADY_SYNCED);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isZero();
		verify(draftFacade, never()).stageFromList(any(), any());
		verify(outboxFacade, never()).enqueue(any(), any(), any());
	}

	@Test
	@DisplayName("syncCatalog — 레거시 미완성 전시라도 sync가 뒤채움을 걸지 않는다(수집은 신규 등록 포착만)")
	void syncCatalog_레거시미완성_뒤채움_안함() {
		// draft 도입 이전 행에만 남는 상태다(현재 승격 경로는 상세를 항상 함께 쓴다).
		// 조기 종료로 목록 뒤쪽을 보지 않게 되면서 이 분기는 어차피 닿지 않는다 — sync에서 뺐다.
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(listData(List.of(data("CAT-OLD", "기존 전시"))));
		given(backfill.findDetailTargetState("CAT-OLD")).willReturn(DetailTargetState.NEEDS_DETAIL);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		synchronizer.syncCatalog();

		verify(outboxFacade, never()).enqueue(eq(OutboxMessageType.FETCH_DETAIL), eq("CAT-OLD"), any());
		verify(catalogClient, never()).fetchDetail(any()); // 인라인 조회는 여전히 없다
	}

	@Test
	@DisplayName("적재 불가 행은 여기서 걸러진다 — 어댑터는 안 거르고 그대로 올려보낸다")
	void 적재불가행_application에서_필터() {
		CatalogExhibitionData 불량 = new CatalogExhibitionData("CAT-BAD2", "", "장소", null, null,
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관", null, null, null, "전시", "서울");
		given(catalogClient.fetchPage(any(), anyInt()))
				.willReturn(listData(List.of(불량, data("CAT-OK2", "정상 전시"))));
		given(backfill.findDetailTargetState("CAT-OK2")).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isEqualTo(1); // 제목 없는 행은 통과 못 한다
		verify(facade, never()).archiveListSnapshot(eq(불량), any());
	}

	@Test
	@DisplayName("순회 — 덜 찬 페이지를 만나면 멈춘다(원천이 마지막 페이지를 명시하지 않으므로)")
	void 순회_덜찬페이지에서_종료() {
		synchronizer = withPageSize(2);
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), eq(1))).willReturn(fullPage("A1", "A2"));   // 꽉 참
		given(catalogClient.fetchPage(any(), eq(2))).willReturn(listData(List.of(data("B1", "덜 찬"))));
		given(backfill.findDetailTargetState(any())).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		synchronizer.syncCatalog();

		verify(catalogClient).fetchPage(any(), eq(2));
		verify(catalogClient, never()).fetchPage(any(), eq(3)); // 2페이지가 덜 찼으니 3페이지는 안 부른다
	}

	@Test
	@DisplayName("조기 종료 — 페이지 전량이 이미 아는 항목이면 다음 페이지를 부르지 않는다")
	void 조기종료_페이지전량_기존항목() {
		synchronizer = withPageSize(2);
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), eq(1))).willReturn(fullPage("K1", "K2"));
		given(facade.allSnapshotted(List.of("K1", "K2"))).willReturn(true); // 전량 known
		given(backfill.findDetailTargetState(any())).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		synchronizer.syncCatalog();

		verify(catalogClient, never()).fetchPage(any(), eq(2));
	}

	@Test
	@DisplayName("조기 종료 안 함 — 하나라도 모르는 항목이 섞이면 계속 순회한다(seq 단조 가정이 깨질 수 있다)")
	void 조기종료_하나라도_신규면_계속() {
		synchronizer = withPageSize(2);
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), eq(1))).willReturn(fullPage("K1", "NEW"));
		given(facade.allSnapshotted(List.of("K1", "NEW"))).willReturn(false);
		given(catalogClient.fetchPage(any(), eq(2))).willReturn(listData(List.of(data("B1", "덜 찬"))));
		given(backfill.findDetailTargetState(any())).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		synchronizer.syncCatalog();

		verify(catalogClient).fetchPage(any(), eq(2)); // 멈추지 않았다
	}

	@Test
	@DisplayName("감사 — 콜마다 한 행씩 남는다(3콜이 1행으로 뭉개지지 않는다)")
	void 감사_콜단위_기록() {
		synchronizer = withPageSize(2);
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), eq(1))).willReturn(fullPage("A1", "A2"));
		given(catalogClient.fetchPage(any(), eq(2))).willReturn(listData(List.of(data("B1", "덜 찬"))));
		given(backfill.findDetailTargetState(any())).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.STAGED);

		synchronizer.syncCatalog();

		verify(facade).recordApiCall(eq(ExternalApi.CULTURE_LIST), eq("realmCode=D000&page=1"),
				eq(ExternalApiOutcome.SUCCESS), any());
		verify(facade).recordApiCall(eq(ExternalApi.CULTURE_LIST), eq("realmCode=D000&page=2"),
				eq(ExternalApiOutcome.SUCCESS), any());
	}

	@Test
	@DisplayName("감사 — 호출이 실패해도 그 콜은 FAILED로 남는다")
	void 감사_실패도_기록() {
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), anyInt())).willThrow(new RuntimeException("전송 실패"));

		try {
			synchronizer.syncCatalog();
		} catch (RuntimeException ignored) {
			// 수집은 실패로 끝난다 — 여기 관심사는 감사 행이다.
		}

		verify(facade).recordApiCall(eq(ExternalApi.CULTURE_LIST), eq("realmCode=D000&page=1"),
				eq(ExternalApiOutcome.FAILED), any());
	}

	@Test
	@DisplayName("인증키 미설정 — 외부를 부르지 않고 스킵한다(감사 행도 없다)")
	void 인증키_미설정_스킵() {
		given(catalogClient.isConfigured()).willReturn(false);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isZero();
		verify(catalogClient, never()).fetchPage(any(), anyInt());
		verify(facade, never()).recordApiCall(any(), any(), any(), any());
	}

	@Test
	@DisplayName("syncCatalog — 기간 비정상(종료<시작) 원천은 스킵하되 벤더 원본은 남긴다")
	void syncCatalog_기간비정상_스킵() {
		LocalDate today = LocalDate.now();
		CatalogExhibitionData invalid = new CatalogExhibitionData("CAT-BAD", "역전 기간", "장소", today,
				today.minusDays(1), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관",
				null, null, null, "전시", "서울");
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(listData(List.of(invalid, data("CAT-OK", "정상 전시"))));
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
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(listData(List.of(data("CAT-RE", "재동기화 전시"))));
		given(backfill.findDetailTargetState("CAT-RE")).willReturn(DetailTargetState.MISSING);
		given(draftFacade.stageFromList(any(), any())).willReturn(ExhibitionDraftFacade.StageOutcome.REFRESHED);

		int staged = synchronizer.syncCatalog();

		assertThat(staged).isZero(); // 갱신은 신규 스테이징 수에 잡히지 않는다
		verify(facade).archiveIngestionRun(any(), eq(0), eq(1), eq(0), eq(0));
	}
}
