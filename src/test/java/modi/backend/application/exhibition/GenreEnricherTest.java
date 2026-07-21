package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.application.exhibition.contract.ExhibitionBackfill;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.draft.DraftEnrichmentService;
import modi.backend.ingestion.application.enricher.GenreEnricher;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.properties.CatalogEnrichProperties;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;

/**
 * GenreEnricher 단위 검증 — <b>전시 아웃박스 기반</b> 장르 처리(스윕 → 드레인, 메시지당 개별 AI 호출 — ADR-12).
 * 핵심 둘: (1) 대상 해소는 draft 우선(스텝 서비스)·전시 폴백(코어 계약) 이원화(ADR-10).
 * (2) 계약 반전(ADR-11) — 분류기 실패는 폴백값이 아니라 예외이고, 그 메시지를 RETRYABLE로 남겨 회복 후 재분류한다.
 */
class GenreEnricherTest {

	private final CatalogEnrichProperties props = new CatalogEnrichProperties(40, 20);

	private ExhibitionBackfill backfill;
	private DraftEnrichmentService draftEnrichment;
	private ExhibitionOutboxFacade outboxFacade;
	private GenreClassifier classifier;
	private GenreEnricher enricher;

	@BeforeEach
	void setUp() {
		backfill = mock(ExhibitionBackfill.class);
		draftEnrichment = mock(DraftEnrichmentService.class);
		outboxFacade = mock(ExhibitionOutboxFacade.class);
		classifier = mock(GenreClassifier.class);
		enricher = new GenreEnricher(backfill, draftEnrichment, outboxFacade, props, classifier);
		when(backfill.findUnclassifiedCatalogExternalIds(anyInt())).thenReturn(List.of());
		when(backfill.resolveGenreInputs(anyCollection())).thenReturn(Map.of());
		when(draftEnrichment.classifyGenreStep(anyString(), any())).thenReturn(false);
		when(outboxFacade.findDue(eq(OutboxMessageType.CLASSIFY_GENRE), anyInt(), any())).thenReturn(List.of());
	}

	private static GenreClassification classification() {
		return new GenreClassification("전시", null, null, null, null, null);
	}

	private static OutboxMessage genreMessage(String externalId) {
		return OutboxMessage.enqueue(OutboxMessageType.CLASSIFY_GENRE, externalId, LocalDateTime.now());
	}

	@Test
	@DisplayName("스윕 — 미분류 레거시 CATALOG를 CLASSIFY_GENRE로 멱등 enqueue한다")
	void enrichGenres_미분류를_메시지로_스윕() {
		when(backfill.findUnclassifiedCatalogExternalIds(anyInt())).thenReturn(List.of("E1", "E2"));

		enricher.enrichGenres();

		verify(outboxFacade).enqueueAll(eq(OutboxMessageType.CLASSIFY_GENRE), eq(List.of("E1", "E2")), any());
	}

	@Test
	@DisplayName("드레인 — 전시 폴백 경로: 메시지당 개별 분류로 정준층에 쓰고 성공 처리한다(ADR-12 단건화)")
	void enrichGenres_전시경로_성공반영() {
		OutboxMessage m1 = genreMessage("E1");
		OutboxMessage m2 = genreMessage("E2");
		when(outboxFacade.findDue(eq(OutboxMessageType.CLASSIFY_GENRE), anyInt(), any()))
				.thenReturn(List.of(m1, m2), List.of());
		when(backfill.resolveGenreInputs(eq(List.of("E1")))).thenReturn(Map.of("E1", classification()));
		when(backfill.resolveGenreInputs(eq(List.of("E2")))).thenReturn(Map.of("E2", classification()));
		when(classifier.classify(any()))
				.thenReturn(GenreResult.ai("사진", GenreProvider.GEMINI, "gemini-2.5-flash"),
						GenreResult.ai("미디어아트", GenreProvider.GEMINI, "gemini-2.5-flash"));

		int total = enricher.enrichGenres();

		verify(classifier, times(2)).classify(any()); // 메시지당 1콜(단건화)
		verify(backfill, times(2)).applyGenreResults(argThat(m -> m.size() == 1), any());
		verify(outboxFacade, times(2)).markSucceeded(any(), any());
		assertThat(total).isEqualTo(2);
	}

	@Test
	@DisplayName("드레인 — draft 우선: draft 대상은 스텝 서비스(3박자)로 가고 전시 정준층엔 쓰지 않는다(ADR-10)")
	void enrichGenres_draft경로_스텝서비스위임() {
		OutboxMessage m1 = genreMessage("D1");
		when(outboxFacade.findDue(eq(OutboxMessageType.CLASSIFY_GENRE), anyInt(), any()))
				.thenReturn(List.of(m1), List.of());
		when(draftEnrichment.classifyGenreStep(eq("D1"), any())).thenReturn(true);

		enricher.enrichGenres();

		verify(draftEnrichment).classifyGenreStep(eq("D1"), any()); // 게이트 충족 시 반영 tx가 EXHIBITION_READY 발행
		verify(backfill, never()).applyGenreResults(any(), any());
		verify(classifier, never()).classify(any()); // 레거시 경로 분류기는 타지 않는다
		verify(outboxFacade).markSucceeded(eq(m1), any());
	}

	@Test
	@DisplayName("드레인 — 분류기 예외(전 공급자 실패)면 그 메시지를 RETRYABLE로 남기고 아무것도 쓰지 않는다(ADR-11)")
	void enrichGenres_분류실패_재시도() {
		OutboxMessage m1 = genreMessage("E1");
		OutboxMessage m2 = genreMessage("D1");
		when(outboxFacade.findDue(eq(OutboxMessageType.CLASSIFY_GENRE), anyInt(), any()))
				.thenReturn(List.of(m1, m2), List.of());
		when(backfill.resolveGenreInputs(eq(List.of("E1")))).thenReturn(Map.of("E1", classification()));
		when(classifier.classify(any())).thenThrow(new GenreClassificationException("전 공급자 실패"));
		when(draftEnrichment.classifyGenreStep(eq("D1"), any()))
				.thenThrow(new GenreClassificationException("전 공급자 실패"));

		enricher.enrichGenres();

		verify(outboxFacade, times(2)).markFailed(any(), eq(OutboxFailureType.RETRYABLE), anyString(), any());
		verify(outboxFacade, never()).markSucceeded(any(), any());
		verify(backfill, never()).applyGenreResults(any(), any());
	}

	@Test
	@DisplayName("드레인 — draft도 전시도 대상이 아니면(이미 분류/사라짐) AI 없이 메시지를 성공 마감한다")
	void enrichGenres_이미분류_AI없이_마감() {
		OutboxMessage m1 = genreMessage("E1");
		when(outboxFacade.findDue(eq(OutboxMessageType.CLASSIFY_GENRE), anyInt(), any()))
				.thenReturn(List.of(m1), List.of());

		enricher.enrichGenres();

		verify(classifier, never()).classify(any()); // 할 일 없으면 AI를 태우지 않는다
		verify(outboxFacade).markSucceeded(eq(m1), any());
	}

	@Test
	@DisplayName("드레인 — 도래 메시지가 없으면 AI를 태우지 않고 즉시 끝낸다")
	void enrichGenres_도래없으면_즉시종료() {
		assertThat(enricher.enrichGenres()).isZero();
		verify(classifier, never()).classify(any());
	}
}
