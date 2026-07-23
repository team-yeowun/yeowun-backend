package modi.backend.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.audit.IngestionRunRecorder;
import modi.backend.ingestion.application.culture.ExhibitionKoreaCultureService;
import modi.backend.ingestion.application.draft.ExhibitionDraftService;
import modi.backend.ingestion.application.genre.ExhibitionAiGenreService;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.application.outbox.OutboxFailures;
import modi.backend.ingestion.application.outbox.StepResult;
import modi.backend.ingestion.application.place.ExhibitionPlaceService;
import modi.backend.ingestion.domain.SyncTrigger;
import modi.backend.ingestion.domain.audit.IngestionRun;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.properties.OutboxProperties;
import modi.backend.support.error.CoreException;

/**
 * 수집 파사드의 <b>스텝 의미론</b> 단위 검증(Mockito) — 소비 수명주기(선별→전이·낙관락 skip)는
 * {@code ExhibitionOutboxService.drain}으로 이동했으므로, 여기선 outboxService mock의 drain을 willAnswer로
 * 핸들러 캡처·실행해(실제 drain과 같은 전이 규칙으로 에뮬레이션) 스텝 의미론을 못박는다: draft 단일 경로
 * (레거시 전시 폴백은 소비 대상 소멸로 제거), 할일없음 성공 마감, PERMANENT 시 draft FAILED 연동,
 * <b>장르 실패 RETRYABLE 고정</b>. 수집 진입점(syncCatalog)의 라우팅·집계(IngestionRun 실엔티티)도 검증한다.
 */
class ExhibitionIngestionOrchestratorTest {

	private final OutboxProperties outboxProps = new OutboxProperties(5, 60L, 3600L, 50, 60000L, 30);

	private ExhibitionKoreaCultureService cultureService;
	private ExhibitionAiGenreService genreService;
	private ExhibitionPlaceService placeService;
	private ExhibitionDraftService draftService;
	private ExhibitionOutboxService outboxService;
	private IngestionRunRecorder runRecorder;
	private ExhibitionIngestionOrchestrator facade;

	@BeforeEach
	void setUp() {
		cultureService = mock(ExhibitionKoreaCultureService.class);
		genreService = mock(ExhibitionAiGenreService.class);
		placeService = mock(ExhibitionPlaceService.class);
		draftService = mock(ExhibitionDraftService.class);
		outboxService = mock(ExhibitionOutboxService.class);
		runRecorder = mock(IngestionRunRecorder.class);
		facade = new ExhibitionIngestionOrchestrator(cultureService, genreService, placeService, draftService,
				outboxService, runRecorder);
		// 유량 정책은 장르 서비스 소유 — 파사드는 물어서 쓴다(모킹 기본값 0이면 드레인 루프가 아예 안 돈다).
		given(genreService.maxBatchesPerRun()).willReturn(2);
		given(genreService.drainBatchSize()).willReturn(2);
	}

	private static CatalogExhibitionData data(String externalId) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, "전시 " + externalId, "장소", today.minusDays(1),
				today.plusDays(10), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관",
				null, null, null, "전시", "서울");
	}

	private static CatalogExhibitionData invalidPeriodData(String externalId) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, "역전 기간", "장소", today, today.minusDays(1),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관", null, null, null, "전시", "서울");
	}

	private static OutboxMessage event(IngestionEventType type, String key) {
		return OutboxMessage.enqueue(type, key, LocalDateTime.now());
	}

	private void fetchedFromList(CatalogExhibitionData... items) {
		given(cultureService.fetchPages(any()))
				.willReturn(new ExhibitionKoreaCultureService.Fetched(List.of(items), items.length));
	}

	/** recorder mock에 캡처된 run — syncCatalog 집계는 실엔티티 수치로 단언한다. */
	private IngestionRun recordedRun() {
		ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
		verify(runRecorder).record(captor.capture());
		return captor.getValue();
	}

	/**
	 * 실제 {@code ExhibitionOutboxService.drain}과 같은 전이 규칙으로 스텝 핸들러를 실행한다 —
	 * SUCCESS→succeed, FAIL→recordFailure(+FAILED_PERMANENT면 콜백), SKIP→무전이·집계 제외.
	 * 파사드가 넘긴 핸들러의 판정을 실엔티티 상태로 관측하는 것이 이 테스트의 판정 재료다.
	 */
	/**
	 * 실제 {@code drain}의 예외 번역 규칙 — 스텝이 던진 예외를 판정으로 바꾸는 책임이 아웃박스 메커니즘으로
	 * 옮겨갔으므로(오케스트레이터 스텝엔 try/catch가 없다), 에뮬레이터도 같은 규칙을 재현해야 스텝 의미론을 볼 수 있다.
	 */
	private StepResult runStep(Function<OutboxMessage, StepResult> step, OutboxMessage message) {
		try {
			return step.apply(message);
		} catch (OptimisticLockingFailureException e) {
			return StepResult.skip();
		} catch (RuntimeException e) {
			// 장르(DETAIL_FETCHED)만 무조건 RETRYABLE — 무기한 정책(ADR-11).
			return StepResult.fail(message.getMessageType() == IngestionEventType.DETAIL_FETCHED
					? OutboxFailureType.RETRYABLE : OutboxFailures.classify(e), OutboxFailures.describe(e));
		}
	}

	private void emulateDrain(List<OutboxMessage> messages, Function<OutboxMessage, StepResult> step,
			Consumer<OutboxMessage> onPermanentFailure) {
		LocalDateTime now = LocalDateTime.now();
		for (OutboxMessage message : messages) {
			StepResult result = runStep(step, message);
			switch (result.outcome()) {
				case SKIP -> { /* 무전이 — 메시지는 PENDING 그대로다 */ }
				case SUCCESS -> message.succeed(now);
				case FAIL -> {
					var policy = message.getMessageType() == IngestionEventType.DETAIL_FETCHED
							? outboxProps.genreRetryPolicy() : outboxProps.retryPolicy();
					message.recordFailure(result.failureType(), result.error(), policy, now);
					if (onPermanentFailure != null && message.getStatus() == OutboxMessageStatus.FAILED_PERMANENT) {
						onPermanentFailure.accept(message);
					}
				}
			}
		}
	}

	/** 콜백형 drain(상세·승격) 스텁 — 핸들러를 캡처해 도래 이벤트에 실행한다(drain은 void라 willAnswer-given 형식). */
	private void dueForCallbackDrain(IngestionEventType type, OutboxMessage... messages) {
		willAnswer(inv -> {
			emulateDrain(List.of(messages), inv.getArgument(1), inv.getArgument(2));
			return null;
		}).given(outboxService).drain(eq(type), ArgumentMatchers.<Function<OutboxMessage, StepResult>>any(),
				ArgumentMatchers.<Consumer<OutboxMessage>>any());
	}

	/** 다중 배치형 drain(장르) 스텁 — 반복은 실제 drain 내부라 여기선 도래분 1회 실행으로 충분하다. */
	private void dueForGenreDrain(OutboxMessage... messages) {
		willAnswer(inv -> {
			emulateDrain(List.of(messages), inv.getArgument(3), null);
			return null;
		}).given(outboxService).drain(eq(IngestionEventType.DETAIL_FETCHED), anyInt(), anyInt(),
				ArgumentMatchers.<Function<OutboxMessage, StepResult>>any());
	}

	/** 기본형 drain(영업시간) 스텁. */
	private void dueForPlainDrain(IngestionEventType type, OutboxMessage... messages) {
		willAnswer(inv -> {
			emulateDrain(List.of(messages), inv.getArgument(1), null);
			return null;
		}).given(outboxService).drain(eq(type), ArgumentMatchers.<Function<OutboxMessage, StepResult>>any());
	}

	// ──────────────────────────────────────────────────────────────────────────
	@Nested
	@DisplayName("syncCatalog — 목록 sync 루프(목록 외 외부 호출 0)")
	class SyncCatalog {

		@Test
		@DisplayName("신규는 draft로 스테이징하고 상세를 인라인 조회하지 않는다 — 런 감사에 신규 1 집계")
		void 신규_스테이징() {
			fetchedFromList(data("CAT-NEW"));
			given(draftService.stageFromList(any(), any())).willReturn(ExhibitionDraftService.StageOutcome.STAGED);

			facade.syncCatalog(SyncTrigger.MANUAL);

			verify(cultureService).archiveListSnapshot(any(), any());
			verify(cultureService, never()).fetchDetail(any()); // 상세는 이벤트 소비로 릴레이가 처리한다
			IngestionRun run = recordedRun();
			assertThat(run.getInserted()).isEqualTo(1);
			assertThat(run.getCompleted()).isZero();
			assertThat(run.getSkipped()).isZero();
			assertThat(run.getDeferred()).isZero();
		}

		@Test
		@DisplayName("SKIPPED(이미 완성/종료 — 가드는 DraftService 소유)는 어느 집계에도 잡히지 않는다")
		void 완성전시_스킵() {
			fetchedFromList(data("CAT-DONE"));
			given(draftService.stageFromList(any(), any())).willReturn(ExhibitionDraftService.StageOutcome.SKIPPED);

			facade.syncCatalog(SyncTrigger.MANUAL);

			IngestionRun run = recordedRun();
			assertThat(run.getInserted()).isZero();
			assertThat(run.getCompleted()).isZero();
			assertThat(run.getSkipped()).isZero();
			assertThat(run.getDeferred()).isZero();
		}

		@Test
		@DisplayName("기간 비정상(종료<시작)은 스킵하되 벤더 원본은 남긴다 — 런 감사에 스킵 1 집계")
		void 기간비정상_스킵_원본보존() {
			fetchedFromList(invalidPeriodData("CAT-BAD"));

			facade.syncCatalog(SyncTrigger.MANUAL);

			verify(cultureService).archiveListSnapshot(any(), any()); // 원천이 뭐라고 했는지는 남는다
			verify(draftService, never()).stageFromList(any(), any());
			assertThat(recordedRun().getSkipped()).isEqualTo(1);
		}

		@Test
		@DisplayName("단건 실패는 연기하고 루프를 계속한다(한 행이 배치 전체를 죽이지 않는다)")
		void 단건실패_연기() {
			fetchedFromList(data("CAT-ERR"), data("CAT-OK"));
			// 단건 실패 삼킴은 스테이징 서비스 소유 — 여기선 그 결과(DEFERRED)를 집계하는지만 본다.
			given(draftService.stageFromList(any(), any()))
					.willReturn(ExhibitionDraftService.StageOutcome.DEFERRED)
					.willReturn(ExhibitionDraftService.StageOutcome.STAGED);

			facade.syncCatalog(SyncTrigger.MANUAL);

			IngestionRun run = recordedRun(); // 신규 1·실패연기 1
			assertThat(run.getInserted()).isEqualTo(1);
			assertThat(run.getDeferred()).isEqualTo(1);
		}

		@Test
		@DisplayName("재sync의 미종료 draft는 갱신(REFRESHED)으로 집계된다 — 신규 스테이징 수엔 잡히지 않는다")
		void 재sync_갱신집계() {
			fetchedFromList(data("CAT-RE"));
			given(draftService.stageFromList(any(), any())).willReturn(ExhibitionDraftService.StageOutcome.REFRESHED);

			facade.syncCatalog(SyncTrigger.MANUAL);

			IngestionRun run = recordedRun();
			assertThat(run.getInserted()).isZero();
			assertThat(run.getCompleted()).isEqualTo(1);
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	@Nested
	@DisplayName("drainDetailFetch — DRAFT_STAGED 소비(상세 3박자)")
	class DrainDetailFetch {

		private final OutboxMessage message = event(IngestionEventType.DRAFT_STAGED, "E1");

		private void due() {
			dueForCallbackDrain(IngestionEventType.DRAFT_STAGED, message);
		}

		@Test
		@DisplayName("draft 우선 — 상세 미해소 draft는 [조회 → applyDetail] 후 성공 마감한다")
		void draft경로_성공() {
			due();
			given(draftService.needsDetail("E1")).willReturn(true);
			given(cultureService.fetchDetail("E1")).willReturn(mock(CultureDetailPayload.class));

			facade.drainDetailFetch();

			verify(draftService).applyDetail(eq("E1"), any(), any());
			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		}

		@Test
		@DisplayName("상세 조회 일시 실패(timeout/5xx류)는 RETRYABLE로 기록한다")
		void 일시실패_RETRYABLE() {
			due();
			given(draftService.needsDetail("E1")).willReturn(true);
			given(cultureService.fetchDetail("E1"))
					.willThrow(new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 API 실패"));

			facade.drainDetailFetch();

			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE);
			verify(draftService, never()).applyDetail(any(), any(), any());
			verify(draftService, never()).markStepPermanentlyFailed(any(), any(), any());
		}

		@Test
		@DisplayName("상세 스텝 미해소 draft가 없으면(재전달·종료·미존재) 외부 호출 없이 할일없음 성공 마감한다")
		void 할일없음_성공마감() {
			due();
			given(draftService.needsDetail("E1")).willReturn(false);

			facade.drainDetailFetch();

			verify(cultureService, never()).fetchDetail(any());
			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		}

		@Test
		@DisplayName("draft 경로 4xx(PERMANENT 굳힘) — draft도 FAILED로 종료한다(영구 미승격 가시화)")
		void 영구실패_draft도_FAILED() {
			due();
			given(draftService.needsDetail("E1")).willReturn(true);
			given(cultureService.fetchDetail("E1")).willThrow(
					HttpClientErrorException.create(HttpStatus.NOT_FOUND, "not found", null, null, null));

			facade.drainDetailFetch();

			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_PERMANENT);
			verify(draftService).markStepPermanentlyFailed(eq("E1"), any(), any());
		}

		@Test
		@DisplayName("반영 중 낙관락 충돌은 skip — 전이 없이 다른 워커에게 맡긴다(성공/실패 기록 없음)")
		void 낙관락_skip() {
			due();
			given(draftService.needsDetail("E1")).willReturn(true);
			given(cultureService.fetchDetail("E1")).willReturn(mock(CultureDetailPayload.class));
			doThrow(new OptimisticLockingFailureException("version")).when(draftService)
					.applyDetail(eq("E1"), any(), any());

			facade.drainDetailFetch(); // 전이 수 집계에서도 빠진다

			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.PENDING); // 무전이
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	@Nested
	@DisplayName("drainGenreClassification — DETAIL_FETCHED 소비(장르 3박자)")
	class DrainGenreClassification {

		private final OutboxMessage message = event(IngestionEventType.DETAIL_FETCHED, "E1");
		private final GenreClassification input = new GenreClassification("전시", null, null, null, null, null);

		private void due() {
			// 장르만 배치 크기 지정 drain이다 — 배치 크기가 장르 축 정책(drainBatchSize)이라 파사드가 값을 넘긴다.
			dueForGenreDrain(message);
		}

		@Test
		@DisplayName("분류 입력이 있으면 [개별 AI 호출 → applyGenre] 후 성공 마감한다")
		void draft경로_성공() {
			due();
			given(draftService.resolveGenreInput("E1")).willReturn(Optional.of(input));
			given(genreService.classify(eq("E1"), any()))
					.willReturn(GenreResult.ai("사진", GenreProvider.GEMINI, "gemini-2.5-flash"));

			facade.drainGenreClassification();

			verify(draftService).applyGenre(eq("E1"), any(), any());
			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		}

		@Test
		@DisplayName("실패는 무조건 RETRYABLE 고정 — PERMANENT로 분류될 예외(IllegalArgumentException)여도 굳히지 않는다")
		void 실패_RETRYABLE_고정() {
			due();
			given(draftService.resolveGenreInput("E1")).willReturn(Optional.of(input));
			// OutboxFailures.classify를 태우면 PERMANENT가 될 예외 — 장르는 무기한 정책이라 굳으면 안 된다(ADR-11).
			given(genreService.classify(eq("E1"), any())).willThrow(new IllegalArgumentException("빈 응답"));

			facade.drainGenreClassification();

			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE);
			verify(draftService, never()).applyGenre(any(), any(), any());
		}

		@Test
		@DisplayName("분류 입력이 비면(이미 분류·종료 draft) AI 호출 없이 할일없음 성공 마감한다")
		void 입력없음_성공마감() {
			due();
			given(draftService.resolveGenreInput("E1")).willReturn(Optional.empty());

			facade.drainGenreClassification();

			verify(genreService, never()).classify(any(), any());
			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	@Nested
	@DisplayName("drainPromotion — DRAFT_READY 소비(승격)")
	class DrainPromotion {

		private final OutboxMessage message = event(IngestionEventType.DRAFT_READY, "E1");

		private void due() {
			dueForCallbackDrain(IngestionEventType.DRAFT_READY, message);
		}

		@Test
		@DisplayName("completePromotion(멱등)에 위임하고 성공 마감한다 — 재전달 no-op도 성공이다")
		void 승격위임_성공마감() {
			due();

			facade.drainPromotion();

			verify(draftService).completePromotion(eq("E1"), any());
			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		}

		@Test
		@DisplayName("낙관락 충돌은 skip — 전이 없이 다른 워커에게 맡긴다")
		void 낙관락_skip() {
			due();
			doThrow(new OptimisticLockingFailureException("version")).when(draftService)
					.completePromotion(eq("E1"), any());

			facade.drainPromotion();

			assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.PENDING); // 무전이
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	@Nested
	@DisplayName("drainPlaceHours — PLACE_HOURS_STALE 소비(발행자는 승격과 syncCatalog 스윕 둘, 소비는 이 한 경로)")
	class DrainPlaceHours {

		@Test
		@DisplayName("스텝은 place 축에 위임하고 성공 마감한다 — 대상 해소·조회·반영·실패기록은 전부 그 안이다")
		void 위임_성공마감() {
			OutboxMessage stale = event(IngestionEventType.PLACE_HOURS_STALE, "부산현대미술관");
			dueForPlainDrain(IngestionEventType.PLACE_HOURS_STALE, stale);

			facade.drainPlaceHours();

			verify(placeService).refreshHours("부산현대미술관");
			assertThat(stale.getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		}

		@Test
		@DisplayName("조회가 실패하면 이벤트는 실패 전이한다(정준층 기록은 place 축이 이미 남겼다)")
		void 조회실패_실패전이() {
			OutboxMessage stale = event(IngestionEventType.PLACE_HOURS_STALE, "부산현대미술관");
			dueForPlainDrain(IngestionEventType.PLACE_HOURS_STALE, stale);
			doThrow(new RuntimeException("timeout")).when(placeService).refreshHours("부산현대미술관");

			facade.drainPlaceHours();

			assertThat(stale.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE);
		}
	}
}
