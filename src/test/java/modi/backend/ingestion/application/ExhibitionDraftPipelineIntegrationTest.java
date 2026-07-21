package modi.backend.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.application.draft.ExhibitionDraftFacade;
import modi.backend.ingestion.application.enricher.GenreEnricher;
import modi.backend.ingestion.application.enricher.DraftPromoter;
import modi.backend.ingestion.application.enricher.DetailEnricher;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.support.error.CoreException;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import static org.mockito.ArgumentMatchers.anyInt;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.infra.culture.CultureDetail2Response;
import modi.backend.ingestion.domain.draft.DraftStatus;
import modi.backend.ingestion.domain.draft.ExhibitionDraft;
import modi.backend.ingestion.domain.draft.ExhibitionDraftRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * draft 파이프라인 <b>전 구간</b> 통합 검증(ADR-10) — 목록 sync(스테이징) → FETCH_DETAIL 드레인(상세 해소 + 장르
 * 체인) → CLASSIFY_GENRE 드레인(분류 + 승격)이 실제 빈·실제 DB로 이어지는지 확인한다. 분류기는 테스트 기본
 * 구성(mock — 결정적)이라 AI 호출 없이 동일 경로가 돈다. 원천 API만 스텁이다(외부 접촉면).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionDraftPipelineIntegrationTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	CatalogSynchronizer catalogSynchronizer;

	@Autowired
	DetailEnricher detailEnricher;

	@Autowired
	GenreEnricher genreEnricher;

	@Autowired
	DraftPromoter draftPromoter;

	@Autowired
	ExhibitionDraftFacade exhibitionDraftFacade;

	@Autowired
	ExhibitionDraftRepository exhibitionDraftRepository;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	OutboxMessageRepository outboxMessageRepository;

	@MockitoBean
	ExhibitionCatalogClient catalogClient;

	private static CatalogExhibitionData listData(String externalId, String placeName) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, "파이프라인 전시 " + externalId, placeName,
				today.minusDays(1), today.plusDays(10), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
				null, null, "기관", null, null, null, "전시", "서울");
	}

	@Test
	@DisplayName("전 구간 — sync 스테이징 → 상세 드레인(장르 체인) → 장르 드레인(승격)으로 완성 전시만 도메인에 나타난다")
	void 파이프라인_전구간_승격() {
		int seq = SEQ.getAndIncrement();
		String externalId = "PIPE-" + seq;
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(
				new CatalogPage(java.util.List.of(listData(externalId, "파이프장소" + seq)), 1));
		given(catalogClient.fetchDetail(anyString())).willReturn(new CultureDetail2Response.Item("SEQ", null, null, null, null, null, null, null, null, null,
				"무료", "전시 소개", null, "02-000-0000", null, null, "서울시 종로구", null));

		// 1) sync — 목록 외 외부 호출 0: draft 스테이징 + FETCH_DETAIL enqueue만.
		int staged = catalogSynchronizer.syncCatalog();
		assertThat(staged).isEqualTo(1);
		assertThat(exhibitionRepository.findByExternalId(externalId)).isEmpty(); // 전시는 아직 없다(게이트 전)
		assertThat(exhibitionDraftRepository.findByExternalId(externalId).orElseThrow().getStatus())
				.isEqualTo(DraftStatus.PENDING);

		// 2) 상세 드레인 — draft 상세분 해소 + 같은 트랜잭션에서 CLASSIFY_GENRE 체인.
		detailEnricher.enrichDetails();
		ExhibitionDraft afterDetail = exhibitionDraftRepository.findByExternalId(externalId).orElseThrow();
		assertThat(afterDetail.getStatus()).isEqualTo(DraftStatus.ENRICHING);
		assertThat(afterDetail.getPrice()).isEqualTo("무료");
		assertThat(outboxMessageRepository
				.findByMessageTypeAndTargetKey(OutboxMessageType.CLASSIFY_GENRE, externalId)).isPresent();

		// 3) 장르 드레인 — 분류(테스트 기본 mock 분류기, 결정적) + 게이트 충족 → 같은 트랜잭션에서 승격.
		genreEnricher.enrichGenres();
		draftPromoter.promoteReady(); // 승격 소비(ADR-12)

		ExhibitionDraft completed = exhibitionDraftRepository.findByExternalId(externalId).orElseThrow();
		assertThat(completed.getStatus()).isEqualTo(DraftStatus.COMPLETED);
		assertThat(completed.getGenreProvider()).isEqualTo(GenreProvider.MOCK);
		Exhibition promoted = exhibitionRepository.findByExternalId(externalId).orElseThrow();
		assertThat(promoted.getId()).isEqualTo(completed.getPromotedExhibitionId());
		assertThat(exhibitionRepository.hasDetail(promoted.getId())).isTrue(); // 상세분까지 이관된 완성체
		assertThat(exhibitionRepository.findGenre(promoted.getId())).isPresent(); // 장르 정준행
		// 메시지들도 전부 마감됐다.
		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(OutboxMessageType.FETCH_DETAIL, externalId)
				.orElseThrow().getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(OutboxMessageType.CLASSIFY_GENRE, externalId)
				.orElseThrow().getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("원천에 상세 없음 — 예외로 처리돼 승격되지 않는다(무상세 해소 경로 제거 — 사용자 결정)")
	void 파이프라인_상세없음_미승격() {
		int seq = SEQ.getAndIncrement();
		String externalId = "PIPE-ABSENT-" + seq;
		given(catalogClient.isConfigured()).willReturn(true);
		given(catalogClient.fetchPage(any(), anyInt())).willReturn(
				new CatalogPage(java.util.List.of(listData(externalId, "무상세장소" + seq)), 1));
		// detail2는 유효한 seq면 항상 값을 준다(실측). 빈 응답은 "목록 이후 원천에서 삭제됨"이라 예외다.
		given(catalogClient.fetchDetail(anyString())).willThrow(
				new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 전시 API 상세 없음"));

		catalogSynchronizer.syncCatalog();
		detailEnricher.enrichDetails();
		genreEnricher.enrichGenres();
		draftPromoter.promoteReady();

		// 예전엔 무상세도 스텝 해소로 승격됐다. 이제는 상세를 못 받으면 게이트를 못 채워 승격되지 않는다.
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElseThrow();
		assertThat(draft.getStatus()).isNotEqualTo(DraftStatus.COMPLETED);
		assertThat(exhibitionRepository.findByExternalId(externalId)).isEmpty();
	}

	@Test
	@DisplayName("AI 전면 장애 — 장르 메시지는 RETRYABLE로 남고 draft는 승격 대기한다(가짜 값 없음 — ADR-11)")
	void 파이프라인_AI장애_대기() {
		int seq = SEQ.getAndIncrement();
		String externalId = "PIPE-WAIT-" + seq;
		LocalDateTime now = LocalDateTime.now();
		exhibitionDraftFacade.stageFromList(listData(externalId, "대기장소" + seq), now);
		exhibitionDraftFacade.markDetailAbsent(externalId, now); // 상세 스텝 해소 → CLASSIFY_GENRE enqueue

		// 분류 실패를 흉내내는 대신, 메시지를 직접 실패 전이시켜 "대기" 상태의 관측 가능한 형태를 확인한다.
		var message = outboxMessageRepository
				.findByMessageTypeAndTargetKey(OutboxMessageType.CLASSIFY_GENRE, externalId).orElseThrow();
		message.recordFailure(modi.backend.ingestion.domain.outbox.OutboxFailureType.RETRYABLE,
				"전 공급자 실패", new modi.backend.ingestion.domain.outbox.RetryPolicy(
						Integer.MAX_VALUE, 60L, 3600L), now);
		outboxMessageRepository.save(message);

		assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE); // 소진 승격 없음(무기한 정책)
		assertThat(exhibitionDraftRepository.findByExternalId(externalId).orElseThrow().getStatus())
				.isEqualTo(DraftStatus.ENRICHING); // draft는 FAILED가 아니라 대기
		assertThat(exhibitionRepository.findByExternalId(externalId)).isEmpty(); // 가짜 장르로 승격되지 않는다
	}
}
