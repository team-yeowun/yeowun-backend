package modi.backend.ingestion.application.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.outbox.OutboxPublisher;
import modi.backend.ingestion.application.progress.ExhibitionProgressService.StageOutcome;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.progress.ExhibitionProgress;
import modi.backend.ingestion.domain.progress.ExhibitionProgressRepository;
import modi.backend.ingestion.domain.progress.ProgressStatus;

/**
 * 진행 상태 서비스 Mockito 단위 — 이벤트 원자 발행(4종 전부 이 서비스)·원장 합류 호출·게이트 검사 지점을 못박는다.
 * 서비스 의존 0(컴포넌트·계약만) — 의존 규칙 §1-1의 산물이라 mock 대상도 컴포넌트다.
 */
class ExhibitionProgressServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 1, 0);

	private ExhibitionProgressRepository repository;
	private SnapshotLedger ledger;
	private ExhibitionAssembler assembler;
	private OutboxPublisher publisher;
	private ExhibitionRegistrar registrar;
	private ExhibitionProgressService service;

	@BeforeEach
	void setUp() {
		repository = mock(ExhibitionProgressRepository.class);
		ledger = mock(SnapshotLedger.class);
		assembler = mock(ExhibitionAssembler.class);
		publisher = mock(OutboxPublisher.class);
		registrar = mock(ExhibitionRegistrar.class);
		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		given(transactionTemplate.execute(any())).willAnswer(inv ->
				((TransactionCallback<?>)inv.getArgument(0)).doInTransaction(null));
		given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));
		service = new ExhibitionProgressService(repository, ledger, assembler, publisher, registrar,
				transactionTemplate);
	}

	private static CatalogExhibitionData data(String externalId, String place) {
		return new CatalogExhibitionData(externalId, "제목", place, null, null, null, null,
				null, null, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("stageFromList(신규) — [목록 원장 + 진행 행 + DRAFT_STAGED·PLACE_STAGED]이 한 흐름에서 원자 실행된다")
	void stage_new_publishes_both_events() {
		given(repository.findByExternalId("EXT-1")).willReturn(Optional.empty());

		StageOutcome outcome = service.stageFromList(data("EXT-1", "국립현대미술관"), NOW);

		assertThat(outcome).isEqualTo(StageOutcome.STAGED);
		then(ledger).should().recordList(any(), eq(NOW));
		then(publisher).should().enqueue(IngestionEventType.DRAFT_STAGED, "EXT-1", NOW);
		then(publisher).should().enqueue(IngestionEventType.PLACE_STAGED, "국립현대미술관", NOW);
	}

	@Test
	@DisplayName("stageFromList(기간 불량) — 벤더 원장은 남기고(SKIPPED) 진행 행·이벤트는 만들지 않는다(ADR-13)")
	void stage_invalid_period_records_ledger_only() {
		CatalogExhibitionData bad = new CatalogExhibitionData("EXT-BAD", "제목", "장소",
				java.time.LocalDate.of(2026, 7, 2), java.time.LocalDate.of(2026, 7, 1),
				null, null, null, null, null, null, null, null, null, null);

		StageOutcome outcome = service.stageFromList(bad, NOW);

		assertThat(outcome).isEqualTo(StageOutcome.SKIPPED);
		then(ledger).should().recordList(eq(bad), eq(NOW));
		then(publisher).should(never()).enqueue(any(), anyString(), any());
	}

	@Test
	@DisplayName("stageFromList(원장 실패) — 원장 upsert가 던지면 그 행만 DEFERRED로 삼키고 배치는 계속된다")
	void stage_defers_on_ledger_failure() {
		org.mockito.BDDMockito.willThrow(new RuntimeException("DB 잠금")).given(ledger).recordList(any(), any());

		StageOutcome outcome = service.stageFromList(data("EXT-1", "장소"), NOW);

		assertThat(outcome).isEqualTo(StageOutcome.DEFERRED);
		then(publisher).should(never()).enqueue(any(), anyString(), any());
	}

	@Test
	@DisplayName("stageFromList(재sync) — 종료 행은 SKIPPED(원장만 갱신), 미종료 행은 미해소 스텝 이벤트를 부활시킨다")
	void resync_refreshes_and_heals() {
		ExhibitionProgress completed = ExhibitionProgress.stage(data("EXT-1", "장소"));
		completed.markDetailResolved(NOW);
		completed.markGenreClassified(NOW);
		completed.complete(9L, NOW);
		given(repository.findByExternalId("EXT-1")).willReturn(Optional.of(completed));
		assertThat(service.stageFromList(data("EXT-1", "장소"), NOW)).isEqualTo(StageOutcome.SKIPPED);

		ExhibitionProgress enriching = ExhibitionProgress.stage(data("EXT-2", "장소"));
		enriching.markDetailResolved(NOW); // 다음 스텝 = CLASSIFY_GENRE
		given(repository.findByExternalId("EXT-2")).willReturn(Optional.of(enriching));
		assertThat(service.stageFromList(data("EXT-2", "장소"), NOW)).isEqualTo(StageOutcome.REFRESHED);
		then(publisher).should().enqueueOrReactivate(IngestionEventType.DETAIL_FETCHED, "EXT-2", NOW);
	}

	@Test
	@DisplayName("applyDetail — [상세 원장 합류 + 마커 + DETAIL_FETCHED] 그리고 게이트 미충족이면 DRAFT_READY는 없다")
	void apply_detail_records_ledger_and_event() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "장소"));
		given(repository.findByExternalId("EXT-1")).willReturn(Optional.of(progress));
		CultureDetailPayload payload = mock(CultureDetailPayload.class);

		service.applyDetail("EXT-1", payload, NOW);

		then(ledger).should().recordDetail("EXT-1", payload);
		then(publisher).should().enqueue(IngestionEventType.DETAIL_FETCHED, "EXT-1", NOW);
		then(publisher).should(never()).enqueue(eq(IngestionEventType.DRAFT_READY), anyString(), any());
		assertThat(progress.needsDetail()).isFalse();
	}

	@Test
	@DisplayName("applyGenre — 게이트를 채운 마지막 스텝의 tx가 DRAFT_READY를 원자 발행한다(폴링 검사자 없음)")
	void apply_genre_fires_ready_when_gate_filled() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "장소"));
		progress.markDetailResolved(NOW); // 상세 먼저 해소 — 그 시점엔 게이트 미충족이었다
		given(repository.findByExternalId("EXT-1")).willReturn(Optional.of(progress));
		GenreResult result = new GenreResult("회화", GenreProvider.GEMINI, "gemini-2.5-flash");

		service.applyGenre("EXT-1", result, NOW);

		then(ledger).should().recordGenre("EXT-1", result, NOW);
		then(publisher).should().enqueue(IngestionEventType.DRAFT_READY, "EXT-1", NOW);
	}

	@Test
	@DisplayName("applyGenre(재전달) — 이미 분류된 행은 no-op(원장·발행 없음)")
	void apply_genre_idempotent() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "장소"));
		progress.markGenreClassified(NOW);
		given(repository.findByExternalId("EXT-1")).willReturn(Optional.of(progress));

		service.applyGenre("EXT-1", new GenreResult("회화", GenreProvider.GEMINI, null), NOW);

		then(ledger).should(never()).recordGenre(anyString(), any(), any());
		then(publisher).should(never()).enqueue(any(), anyString(), any());
	}

	@Test
	@DisplayName("completePromotion — [게이트 재검사 → 어셈블 → 등록(멱등) → COMPLETED]가 완주하고, 게이트 미충족이면 no-op")
	void complete_promotion() {
		ExhibitionProgress ready = ExhibitionProgress.stage(data("EXT-1", "장소"));
		ready.markDetailResolved(NOW);
		ready.markGenreClassified(NOW);
		given(repository.findByExternalId("EXT-1")).willReturn(Optional.of(ready));
		ExhibitionRegistration registration = new ExhibitionRegistration("EXT-1", "제목", "장소", null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				"회화", GenreProvider.GEMINI, null);
		given(assembler.assemble("EXT-1")).willReturn(registration);
		given(registrar.register(registration, NOW)).willReturn(new ExhibitionRegistrar.Registered(42L));

		service.completePromotion("EXT-1", NOW);
		assertThat(ready.getStatus()).isEqualTo(ProgressStatus.COMPLETED);
		assertThat(ready.getPromotedExhibitionId()).isEqualTo(42L);

		// 게이트 미충족(잔존 메시지) — 어셈블·등록이 아예 불리지 않는다("반쪽 어셈블" 상태 표현 불가).
		ExhibitionProgress notReady = ExhibitionProgress.stage(data("EXT-2", "장소"));
		given(repository.findByExternalId("EXT-2")).willReturn(Optional.of(notReady));
		service.completePromotion("EXT-2", NOW);
		then(assembler).should(never()).assemble("EXT-2");
	}

	@Test
	@DisplayName("markStepPermanentlyFailed — 진행을 FAILED로 가시화한다(조용한 영구 미승격 금지, 상세·장르·승격 공용)")
	void permanent_failure_visualized() {
		ExhibitionProgress progress = ExhibitionProgress.stage(data("EXT-1", "장소"));
		given(repository.findByExternalId("EXT-1")).willReturn(Optional.of(progress));

		service.markStepPermanentlyFailed("EXT-1", "AI 3회 소진", NOW);

		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.FAILED);
		assertThat(progress.getLastError()).isEqualTo("AI 3회 소진");
	}
}
