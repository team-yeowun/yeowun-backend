package modi.backend.ingestion.application.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.draft.ExhibitionDraft;
import modi.backend.ingestion.domain.draft.ExhibitionDraftRepository;
import modi.backend.ingestion.domain.outbox.IngestionEventType;

/**
 * 스테이징 <b>가드</b> 단위 검증(Mockito) — "이미 승격(또는 영구 실패)된 draft는 재스테이징하지 않는다"는
 * 스테이징 규칙이라 파사드가 아니라 이 서비스가 소유한다. 승격된 전시는 반드시 종료(COMPLETED) draft를
 * 남기므로(draft 단일 경로 — 레거시 뒤채움 계약 삭제로 코어 존재 판정 가드가 terminal draft 검사로 대체됐다)
 * terminal 검사가 파이프라인 중복 가동(AI 콜 낭비)을 막는다.
 */
class ExhibitionDraftServiceTest {

	private ExhibitionDraftRepository repository;
	private ExhibitionOutboxService outboxService;
	private ExhibitionDraftService service;

	@BeforeEach
	void setUp() {
		repository = mock(ExhibitionDraftRepository.class);
		outboxService = mock(ExhibitionOutboxService.class);
		// 트랜잭션 경계는 이 단위 테스트의 관심사가 아니다 — 콜백을 그대로 실행해 스테이징 로직만 본다.
		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		given(transactionTemplate.execute(any())).willAnswer(inv ->
				inv.getArgument(0, TransactionCallback.class).doInTransaction(mock(TransactionStatus.class)));
		service = new ExhibitionDraftService(repository, mock(ExhibitionRegistrar.class), outboxService,
				transactionTemplate);
	}

	@Test
	@DisplayName("단건 실패는 DEFERRED로 삼킨다 — 배치를 죽이지 않는 판단은 스테이징의 지식이다(호출부는 집계만)")
	void stageFromList_singleFailure_returnsDeferred() {
		given(repository.findByExternalId(any())).willThrow(new RuntimeException("row lock"));

		assertThat(service.stageFromList(data("CAT-ERR"), LocalDateTime.now()))
				.isEqualTo(ExhibitionDraftService.StageOutcome.DEFERRED);
	}

	private static CatalogExhibitionData data(String externalId) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, "전시", "전시장", today, today.plusDays(7),
				null, null, null, null, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("종료(terminal) draft는 SKIPPED — 저장도 이벤트 발행도 없다(재스테이징 가드)")
	void stageFromList_terminalDraft_skipsWithoutStaging() {
		ExhibitionDraft terminal = ExhibitionDraft.stage(data("CAT-DONE"));
		terminal.fail("영구 실패", LocalDateTime.now()); // FAILED = terminal(승격 완료 draft와 동일 규약)
		given(repository.findByExternalId("CAT-DONE")).willReturn(Optional.of(terminal));

		ExhibitionDraftService.StageOutcome outcome = service.stageFromList(data("CAT-DONE"), LocalDateTime.now());

		assertThat(outcome).isEqualTo(ExhibitionDraftService.StageOutcome.SKIPPED);
		verify(repository, never()).save(any());
		verify(outboxService, never()).enqueue(any(), any(), any());
	}

	@Test
	@DisplayName("미스테이징 신규는 [draft 저장 + DRAFT_STAGED 발행]으로 스테이징된다(원자 발행 계약)")
	void stageFromList_new_stagesAndPublishesDraftStaged() {
		given(repository.findByExternalId("CAT-NEW")).willReturn(Optional.empty());
		given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

		ExhibitionDraftService.StageOutcome outcome = service.stageFromList(data("CAT-NEW"), LocalDateTime.now());

		assertThat(outcome).isEqualTo(ExhibitionDraftService.StageOutcome.STAGED);
		verify(repository).save(any());
		verify(outboxService).enqueue(eq(IngestionEventType.DRAFT_STAGED), eq("CAT-NEW"), any());
	}
}
