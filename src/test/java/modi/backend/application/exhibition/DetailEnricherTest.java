package modi.backend.application.exhibition;

import modi.backend.ingestion.infra.culture.CultureDetail2Response;
import modi.backend.ingestion.application.enricher.DetailEnricher;
import modi.backend.application.exhibition.contract.DetailTargetState;
import modi.backend.ingestion.application.draft.DraftEnrichmentService;
import modi.backend.ingestion.application.draft.ExhibitionDraftFacade;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.application.ExhibitionSyncFacade;
import modi.backend.application.exhibition.contract.ExhibitionBackfill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestion.properties.OutboxProperties;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.support.error.CoreException;

/**
 * DetailEnricher 단위 검증 — <b>현행 최대 갭 해소</b>의 핵심: 상세 재시도 선별을 통합 작업큐 <b>읽기</b>로 배선한다.
 * 도래한 FETCH_DETAIL 작업만 집어 상세를 재조회하고, 결과에 따라 성공/재시도로 전이한다. 외부 호출은 트랜잭션 밖이다.
 */
class DetailEnricherTest {

	private final OutboxProperties props = new OutboxProperties(5, 60L, 3600L, 50, 60000L, 30);

	private static OutboxMessage detailJob(String externalId) {
		return OutboxMessage.enqueue(OutboxMessageType.FETCH_DETAIL, externalId, LocalDateTime.now());
	}

	private static CultureDetail2Response.Item detail() {
		return new CultureDetail2Response.Item("SEQ", null, null, null, null, null, null, null, null, null,
				"무료", null, null, null, null, null, null, "PLACE-1");
	}

	@Test
	@DisplayName("선별은 작업 읽기로 — 도래한 FETCH_DETAIL만 집어 상세를 채우고 성공 처리한다")
	void 상세필요_채우고_성공() {
		ExhibitionOutboxFacade jobFacade = mock(ExhibitionOutboxFacade.class);
		ExhibitionSyncFacade facade = mock(ExhibitionSyncFacade.class);
		ExhibitionBackfill backfill = mock(ExhibitionBackfill.class);
		ExhibitionCatalogClient client = mock(ExhibitionCatalogClient.class);
		ExhibitionDraftFacade draftFacade = mock(ExhibitionDraftFacade.class);
		OutboxMessage job = detailJob("E1");
		when(jobFacade.findDue(eq(OutboxMessageType.FETCH_DETAIL), anyInt(), any())).thenReturn(List.of(job));
		when(backfill.findDetailTargetState("E1")).thenReturn(DetailTargetState.NEEDS_DETAIL);
		when(client.fetchDetail("E1")).thenReturn(detail());
		DetailEnricher enricher = new DetailEnricher(jobFacade, facade, backfill, draftFacade,
				new DraftEnrichmentService(draftFacade, client, mock(GenreClassifier.class)), client, props);

		enricher.enrichDetails();

		verify(facade).applyLegacyDetail(eq("E1"), any());
		verify(jobFacade).markSucceeded(eq(job), any());
	}

	@Test
	@DisplayName("상세 조회가 일시 실패하면 RETRYABLE로 기록한다(timeout/5xx류 → 백오프 재시도)")
	void 상세실패_재시도기록() {
		ExhibitionOutboxFacade jobFacade = mock(ExhibitionOutboxFacade.class);
		ExhibitionSyncFacade facade = mock(ExhibitionSyncFacade.class);
		ExhibitionBackfill backfill = mock(ExhibitionBackfill.class);
		ExhibitionCatalogClient client = mock(ExhibitionCatalogClient.class);
		ExhibitionDraftFacade draftFacade = mock(ExhibitionDraftFacade.class);
		OutboxMessage job = detailJob("E1");
		when(jobFacade.findDue(eq(OutboxMessageType.FETCH_DETAIL), anyInt(), any())).thenReturn(List.of(job));
		when(backfill.findDetailTargetState("E1")).thenReturn(DetailTargetState.NEEDS_DETAIL);
		when(client.fetchDetail("E1"))
				.thenThrow(new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 API 실패"));
		DetailEnricher enricher = new DetailEnricher(jobFacade, facade, backfill, draftFacade,
				new DraftEnrichmentService(draftFacade, client, mock(GenreClassifier.class)), client, props);

		enricher.enrichDetails();

		verify(jobFacade).markFailed(eq(job), eq(OutboxFailureType.RETRYABLE), any(), any());
		verify(facade, never()).applyLegacyDetail(any(), any());
	}

	@Test
	@DisplayName("이미 다른 경로가 상세를 채웠으면 외부 호출 없이 성공 처리한다")
	void 이미완성_성공마감() {
		ExhibitionOutboxFacade jobFacade = mock(ExhibitionOutboxFacade.class);
		ExhibitionSyncFacade facade = mock(ExhibitionSyncFacade.class);
		ExhibitionBackfill backfill = mock(ExhibitionBackfill.class);
		ExhibitionCatalogClient client = mock(ExhibitionCatalogClient.class);
		ExhibitionDraftFacade draftFacade = mock(ExhibitionDraftFacade.class);
		OutboxMessage job = detailJob("E1");
		when(jobFacade.findDue(eq(OutboxMessageType.FETCH_DETAIL), anyInt(), any())).thenReturn(List.of(job));
		when(backfill.findDetailTargetState("E1")).thenReturn(DetailTargetState.ALREADY_SYNCED);
		DetailEnricher enricher = new DetailEnricher(jobFacade, facade, backfill, draftFacade,
				new DraftEnrichmentService(draftFacade, client, mock(GenreClassifier.class)), client, props);

		enricher.enrichDetails();

		verify(client, never()).fetchDetail(any());
		verify(jobFacade).markSucceeded(eq(job), any());
	}

	@Test
	@DisplayName("전시가 아직 없으면(신규 상세실패분) RETRYABLE로 두어 다음 동기화 후 재처리되게 한다")
	void 전시미적재_재시도로_남긴다() {
		ExhibitionOutboxFacade jobFacade = mock(ExhibitionOutboxFacade.class);
		ExhibitionSyncFacade facade = mock(ExhibitionSyncFacade.class);
		ExhibitionBackfill backfill = mock(ExhibitionBackfill.class);
		ExhibitionCatalogClient client = mock(ExhibitionCatalogClient.class);
		ExhibitionDraftFacade draftFacade = mock(ExhibitionDraftFacade.class);
		OutboxMessage job = detailJob("E1");
		when(jobFacade.findDue(eq(OutboxMessageType.FETCH_DETAIL), anyInt(), any())).thenReturn(List.of(job));
		when(backfill.findDetailTargetState("E1")).thenReturn(DetailTargetState.MISSING);
		DetailEnricher enricher = new DetailEnricher(jobFacade, facade, backfill, draftFacade,
				new DraftEnrichmentService(draftFacade, client, mock(GenreClassifier.class)), client, props);

		enricher.enrichDetails();

		verify(client, never()).fetchDetail(any());
		verify(jobFacade).markFailed(eq(job), eq(OutboxFailureType.RETRYABLE), any(), any());
	}

	@Test
	@DisplayName("draft 우선 — 상세 미해소 draft가 있으면 draft 경로로 반영하고 성공 처리한다(전시 경로 미진입)")
	void draft경로_상세반영() {
		ExhibitionOutboxFacade jobFacade = mock(ExhibitionOutboxFacade.class);
		ExhibitionSyncFacade facade = mock(ExhibitionSyncFacade.class);
		ExhibitionBackfill backfill = mock(ExhibitionBackfill.class);
		ExhibitionCatalogClient client = mock(ExhibitionCatalogClient.class);
		ExhibitionDraftFacade draftFacade = mock(ExhibitionDraftFacade.class);
		OutboxMessage job = detailJob("E1");
		when(jobFacade.findDue(eq(OutboxMessageType.FETCH_DETAIL), anyInt(), any())).thenReturn(List.of(job));
		when(draftFacade.needsDetail("E1")).thenReturn(true);
		when(client.fetchDetail("E1")).thenReturn(detail());
		DetailEnricher enricher = new DetailEnricher(jobFacade, facade, backfill, draftFacade,
				new DraftEnrichmentService(draftFacade, client, mock(GenreClassifier.class)), client, props);

		enricher.enrichDetails();

		verify(draftFacade).applyDetail(eq("E1"), any(), any()); // 장르 스텝 체인은 draft 파사드 트랜잭션 안에서 걸린다
		verify(facade, never()).applyLegacyDetail(any(), any()); // 전시 폴백 경로로 새지 않는다
		verify(jobFacade).markSucceeded(eq(job), any());
	}

	@Test
	@DisplayName("draft 경로 — 필수 스텝이 PERMANENT로 굳으면 draft도 FAILED로 종료한다(영구 미승격 가시화)")
	void draft경로_영구실패_draft도_FAILED() {
		ExhibitionOutboxFacade jobFacade = mock(ExhibitionOutboxFacade.class);
		ExhibitionSyncFacade facade = mock(ExhibitionSyncFacade.class);
		ExhibitionBackfill backfill = mock(ExhibitionBackfill.class);
		ExhibitionCatalogClient client = mock(ExhibitionCatalogClient.class);
		ExhibitionDraftFacade draftFacade = mock(ExhibitionDraftFacade.class);
		OutboxMessage job = detailJob("E1");
		when(jobFacade.findDue(eq(OutboxMessageType.FETCH_DETAIL), anyInt(), any())).thenReturn(List.of(job));
		when(draftFacade.needsDetail("E1")).thenReturn(true);
		// 4xx = PERMANENT 분류. 실제 파사드처럼 markFailed가 엔티티 전이를 일으키게 스텁한다(상태 관측이 판정 재료라서).
		when(client.fetchDetail("E1")).thenThrow(
				org.springframework.web.client.HttpClientErrorException.create(
						org.springframework.http.HttpStatus.NOT_FOUND, "not found", null, null, null));
		org.mockito.Mockito.doAnswer(inv -> {
			OutboxMessage m = inv.getArgument(0);
			m.recordFailure(inv.getArgument(1), inv.getArgument(2), props.retryPolicy(), inv.getArgument(3));
			return null;
		}).when(jobFacade).markFailed(any(), any(), any(), any());
		DetailEnricher enricher = new DetailEnricher(jobFacade, facade, backfill, draftFacade,
				new DraftEnrichmentService(draftFacade, client, mock(GenreClassifier.class)), client, props);

		enricher.enrichDetails();

		verify(jobFacade).markFailed(eq(job), eq(OutboxFailureType.PERMANENT), any(), any());
		verify(draftFacade).markStepPermanentlyFailed(eq("E1"), any(), any());
	}

	@Test
	@DisplayName("도래 작업이 없으면 외부 호출 없이 끝낸다")
	void 도래없음_무호출() {
		ExhibitionOutboxFacade jobFacade = mock(ExhibitionOutboxFacade.class);
		ExhibitionSyncFacade facade = mock(ExhibitionSyncFacade.class);
		ExhibitionBackfill backfill = mock(ExhibitionBackfill.class);
		ExhibitionCatalogClient client = mock(ExhibitionCatalogClient.class);
		ExhibitionDraftFacade draftFacade = mock(ExhibitionDraftFacade.class);
		when(jobFacade.findDue(eq(OutboxMessageType.FETCH_DETAIL), anyInt(), any())).thenReturn(List.of());
		DetailEnricher enricher = new DetailEnricher(jobFacade, facade, backfill, draftFacade,
				new DraftEnrichmentService(draftFacade, client, mock(GenreClassifier.class)), client, props);

		enricher.enrichDetails();

		verify(client, never()).fetchDetail(any());
	}
}
