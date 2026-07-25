package modi.backend.ingestion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.application.exhibition.contract.PlaceRegistrar;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.audit.IngestionRunRecorder;
import modi.backend.ingestion.application.culture.ExhibitionKoreaCultureService;
import modi.backend.ingestion.application.genre.ExhibitionAiGenreService;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.application.outbox.StepResult;
import modi.backend.ingestion.application.place.ExhibitionPlaceService;
import modi.backend.ingestion.application.progress.ExhibitionProgressService;
import modi.backend.ingestion.application.progress.ExhibitionProgressService.StageOutcome;
import modi.backend.ingestion.domain.SyncTrigger;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;

/**
 * 오케스트레이터 Mockito 단위 — 스텝 합성(누구를 어떤 순서로)과 소비 매핑(이벤트→스텝·영구실패 콜백)만
 * 검증한다. 수명주기(전이·백오프)는 OutboxService, 상태 규칙은 ProgressService 테스트의 몫이다.
 */
class ExhibitionIngestionOrchestratorTest {

	private ExhibitionKoreaCultureService cultureService;
	private ExhibitionAiGenreService genreService;
	private ExhibitionPlaceService placeService;
	private ExhibitionProgressService progressService;
	private ExhibitionOutboxService outboxService;
	private ExhibitionIngestionOrchestrator orchestrator;

	@BeforeEach
	void setUp() {
		cultureService = mock(ExhibitionKoreaCultureService.class);
		genreService = mock(ExhibitionAiGenreService.class);
		placeService = mock(ExhibitionPlaceService.class);
		progressService = mock(ExhibitionProgressService.class);
		outboxService = mock(ExhibitionOutboxService.class);
		orchestrator = new ExhibitionIngestionOrchestrator(cultureService, genreService, placeService,
				progressService, outboxService, new IngestionRunRecorder(run -> run));
	}

	private static CatalogExhibitionData item(String externalId) {
		return new CatalogExhibitionData(externalId, "제목", "장소", null, null, null, null,
				null, null, null, null, null, null, null, null);
	}

	private static CatalogExhibitionData invalidPeriodItem() {
		return new CatalogExhibitionData("EXT-BAD", "제목", "장소",
				java.time.LocalDate.of(2026, 7, 2), java.time.LocalDate.of(2026, 7, 1),
				null, null, null, null, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("syncCatalog — 목록만 받고 행마다 스테이징에 위임한다(기간 불량 판단도 스테이징 소유 — 원장은 불량 행도 남긴다)")
	void sync_stages_each_item() {
		given(cultureService.fetchPages(any())).willReturn(List.of(item("EXT-1"), invalidPeriodItem()));
		given(progressService.stageFromList(any(), any())).willReturn(StageOutcome.STAGED);

		orchestrator.syncCatalog(SyncTrigger.SCHEDULE);

		then(progressService).should().stageFromList(eq(item("EXT-1")), any());
		then(progressService).should().stageFromList(eq(invalidPeriodItem()), any()); // 원장 기록 위해 위임된다
		then(placeService).shouldHaveNoInteractions(); // D4 — 스윕 없음
	}

	@Test
	@DisplayName("소비 매핑 — 4종 이벤트가 각자의 스텝으로, 전시 축 3종엔 영구실패 가시화 콜백이 달린다(D5: 장르 포함)")
	void drain_mappings() {
		orchestrator.consumeDetailFetch();
		then(outboxService).should().consume(eq(IngestionEventType.DRAFT_STAGED), any(), any());

		given(genreService.consumeBatchSize()).willReturn(20);
		given(genreService.maxBatchesPerRun()).willReturn(3);
		orchestrator.consumeGenreClassification();
		then(outboxService).should().consume(eq(IngestionEventType.DETAIL_FETCHED), eq(20), eq(3), any(), any());

		orchestrator.consumePromotion();
		then(outboxService).should().consume(eq(IngestionEventType.DRAFT_READY), any(), any());

		orchestrator.consumePlaceInitialization();
		// 전시장 축은 진행 상태와 무관 — 콜백 없는 오버로드.
		then(outboxService).should().consume(eq(IngestionEventType.PLACE_STAGED), any());
	}

	@Test
	@DisplayName("detailStep — ① 판정이 거짓이면 외부 호출 없이 성공 마감(멱등 소비), 참이면 ②조회→③반영 순서다")
	void detail_step_composition() {
		StepCapture capture = captureDetailStep();

		given(progressService.needsDetail("EXT-1")).willReturn(false);
		capture.run("EXT-1");
		then(cultureService).should(never()).fetchDetail(anyString());

		given(progressService.needsDetail("EXT-2")).willReturn(true);
		CultureDetailPayload payload = mock(CultureDetailPayload.class);
		given(cultureService.fetchDetail("EXT-2")).willReturn(payload);
		capture.run("EXT-2");
		then(progressService).should().applyDetail(eq("EXT-2"), eq(payload), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("genreStep — 입력은 원장(culture)에서 조립하고, 개별 호출 결과를 진행 반영에 넘긴다")
	void genre_step_composition() {
		StepCapture capture = captureGenreStep();
		given(progressService.needsGenre("EXT-1")).willReturn(true);
		GenreClassification input = new GenreClassification("제목", null, null, "장소", null, null);
		given(cultureService.genreInputOf("EXT-1")).willReturn(Optional.of(input));
		GenreResult result = new GenreResult("회화", GenreProvider.GEMINI, null);
		given(genreService.classify("EXT-1", input)).willReturn(result);

		capture.run("EXT-1");

		then(progressService).should().applyGenre(eq("EXT-1"), eq(result), any());
	}

	@Test
	@DisplayName("placeInitStep — 기존 전시장이면 마크만 하고 구글 호출이 없다(장소당 1콜·재검증 없음)")
	void place_init_existing_skips_google() {
		StepCapture capture = capturePlaceStep();
		given(progressService.findAnyExternalIdByPlaceKey("장소")).willReturn(Optional.of("EXT-1"));
		given(cultureService.catalogDataOf("EXT-1")).willReturn(Optional.of(item("EXT-1")));
		given(placeService.resolvePlace(any())).willReturn(new PlaceRegistrar.Resolved(7L, "장소", false));

		capture.run("장소");

		then(progressService).should().markPlaceOutcome(eq("장소"), eq(false), any());
		then(placeService).should(never()).read(any());
	}

	// ── 스텝 캡처 헬퍼 — consume에 넘겨진 스텝 함수를 잡아 직접 실행한다 ─────────────────

	private interface StepCapture {
		void run(String targetKey);
	}

	@SuppressWarnings("unchecked")
	private StepCapture captureDetailStep() {
		var captor = org.mockito.ArgumentCaptor
				.forClass((Class<java.util.function.Function<OutboxMessage, StepResult>>)(Class<?>)java.util.function.Function.class);
		orchestrator.consumeDetailFetch();
		then(outboxService).should().consume(eq(IngestionEventType.DRAFT_STAGED), captor.capture(), any());
		return key -> captor.getValue()
				.apply(OutboxMessage.enqueue(IngestionEventType.DRAFT_STAGED, key, LocalDateTime.now()));
	}

	@SuppressWarnings("unchecked")
	private StepCapture captureGenreStep() {
		var captor = org.mockito.ArgumentCaptor
				.forClass((Class<java.util.function.Function<OutboxMessage, StepResult>>)(Class<?>)java.util.function.Function.class);
		given(genreService.consumeBatchSize()).willReturn(10);
		given(genreService.maxBatchesPerRun()).willReturn(1);
		orchestrator.consumeGenreClassification();
		then(outboxService).should().consume(eq(IngestionEventType.DETAIL_FETCHED), anyInt(), anyInt(),
				captor.capture(), any());
		return key -> captor.getValue()
				.apply(OutboxMessage.enqueue(IngestionEventType.DETAIL_FETCHED, key, LocalDateTime.now()));
	}

	@SuppressWarnings("unchecked")
	private StepCapture capturePlaceStep() {
		var captor = org.mockito.ArgumentCaptor
				.forClass((Class<java.util.function.Function<OutboxMessage, StepResult>>)(Class<?>)java.util.function.Function.class);
		orchestrator.consumePlaceInitialization();
		then(outboxService).should().consume(eq(IngestionEventType.PLACE_STAGED), captor.capture());
		return key -> captor.getValue()
				.apply(OutboxMessage.enqueue(IngestionEventType.PLACE_STAGED, key, LocalDateTime.now()));
	}
}
