package modi.backend.ingestion.application;

import modi.backend.ingestion.infra.culture.KoreaCultureDto;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.application.draft.ExhibitionDraftFacade;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.domain.draft.DraftStatus;
import modi.backend.ingestion.domain.draft.ExhibitionDraft;
import modi.backend.ingestion.domain.draft.ExhibitionDraftRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.infra.exhibition.hours.PlaceHoursJpaRepository;

/**
 * 아웃박스 원자성(ADR-10) 통합 검증 — draft 파이프라인의 두 트랜잭션 경계를 실제 DB로 확인한다:
 * (1) <b>스테이징</b>: [draft 저장 + FETCH_DETAIL enqueue]가 한 트랜잭션의 산물로 함께 남는다.
 * (2) <b>승격</b>: 마지막 필수 스텝(장르) 반영과 같은 트랜잭션에서 [전시장 resolve → 전시 생성 → 상세 satellite →
 *     장르 정준행 → 영업시간 재검증 enqueue → draft 종료]가 전부 남는다. 재전달(같은 장르 재반영)은 멱등이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionSyncAtomicityIntegrationTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionDraftFacade exhibitionDraftFacade;

	@Autowired
	ExhibitionDraftRepository exhibitionDraftRepository;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Autowired
	PlaceHoursJpaRepository placeHoursRepository;

	@Autowired
	OutboxMessageRepository outboxMessageRepository;

	private static CatalogExhibitionData listData(String externalId, String placeName) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, "스테이징 전시 " + externalId, placeName,
				today.minusDays(1), today.plusDays(10), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
				null, null, "기관", null, null, null, "전시", "서울");
	}

	@Test
	@DisplayName("스테이징 — draft 저장과 FETCH_DETAIL enqueue가 한 트랜잭션의 산물로 함께 남는다")
	void stage_원자적_enqueue() {
		int seq = SEQ.getAndIncrement();
		String externalId = "STAGE-" + seq;

		exhibitionDraftFacade.stageFromList(listData(externalId, "스테이징장소" + seq), LocalDateTime.now());

		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElseThrow();
		assertThat(draft.getStatus()).isEqualTo(DraftStatus.PENDING); // draft가 남았고
		OutboxMessage message = outboxMessageRepository
				.findByMessageTypeAndTargetKey(OutboxMessageType.FETCH_DETAIL, externalId).orElseThrow();
		assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.PENDING); // 같은 트랜잭션의 스텝 메시지도 남았다
	}

	@Test
	@DisplayName("승격 — 마지막 스텝 tx가 EXHIBITION_READY를 원자 기록하고, 소비가 전시·재검증 enqueue·draft 종료를 완주한다(ADR-12)")
	void promote_승격_원자성() {
		int seq = SEQ.getAndIncrement();
		String externalId = "PROMOTE-" + seq;
		String placeName = "승격장소" + seq;
		LocalDateTime now = LocalDateTime.now();
		// 기존 장소 + 오래된 정준 영업시간 — 승격 시 재검증 가드(기존 장소만·최소 간격)를 통과하는 조건.
		Long placeId = exhibitionPlaceRepository.save(
				ExhibitionPlace.createFromList(placeName, null, null, null, null)).getId();
		placeHoursRepository.save(PlaceHours.first(placeId, "매일 10:00~18:00",
				PlaceHoursStatus.SUCCEEDED, PlaceHoursVendor.GOOGLE, now.minusDays(60)));

		exhibitionDraftFacade.stageFromList(listData(externalId, placeName), now);
		exhibitionDraftFacade.applyDetail(externalId, new KoreaCultureDto.Detail2Response.Item("SEQ", null, null, null, null, null, null, null, null, null,
				"무료", "전시 소개", null, "02-000-0000", null, null, "서울시 종로구", null), now);
		exhibitionDraftFacade.applyGenre(externalId,
				GenreResult.ai("사진", GenreProvider.GEMINI, "gemini-2.5-flash"), now);

		// 발행 측 원자성 — 마지막 스텝(장르) 트랜잭션의 산물로 승격 신호가 남았고, 전시는 아직 없다(소비 전).
		OutboxMessage ready = outboxMessageRepository
				.findByMessageTypeAndTargetKey(OutboxMessageType.EXHIBITION_READY, externalId).orElseThrow();
		assertThat(ready.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);
		assertThat(exhibitionRepository.findByExternalId(externalId)).isEmpty();

		exhibitionDraftFacade.completePromotion(externalId, now); // 소비(멱등) — 등록·재검증 enqueue·draft 종료 완주

		// 소비 산물 전부가 남았다 — 전시 코어(장소 연결)·draft 종료·재검증 메시지.
		Exhibition promoted = exhibitionRepository.findByExternalId(externalId).orElseThrow();
		assertThat(promoted.getExhibitionPlaceId()).isEqualTo(placeId);
		assertThat(exhibitionRepository.hasDetail(promoted.getId())).isTrue();
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElseThrow();
		assertThat(draft.getStatus()).isEqualTo(DraftStatus.COMPLETED);
		assertThat(draft.getPromotedExhibitionId()).isEqualTo(promoted.getId());
		String placeKey = exhibitionPlaceRepository.findById(placeId).orElseThrow().getPlaceKey();
		assertThat(outboxMessageRepository
				.findByMessageTypeAndTargetKey(OutboxMessageType.REFRESH_PLACE_HOURS, placeKey)).isPresent();
	}

	@Test
	@DisplayName("승격 게이트 — 상세 스텝이 미해소면 장르가 와도 승격되지 않는다(draft가 in-flight 상태를 단독 보유)")
	void promote_게이트_미충족_대기() {
		int seq = SEQ.getAndIncrement();
		String externalId = "GATE-" + seq;
		LocalDateTime now = LocalDateTime.now();
		exhibitionDraftFacade.stageFromList(listData(externalId, "게이트장소" + seq), now);

		// 상세 해소 전 장르부터 도착(순서 역전) — 게이트가 막는다.
		exhibitionDraftFacade.applyGenre(externalId,
				GenreResult.ai("사진", GenreProvider.GEMINI, "gemini-2.5-flash"), now);

		assertThat(exhibitionRepository.findByExternalId(externalId)).isEmpty(); // 전시는 아직 없다
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElseThrow();
		assertThat(draft.getStatus()).isEqualTo(DraftStatus.ENRICHING); // 장르만 반영된 in-flight
		assertThat(draft.getGenreKeyword()).isEqualTo("사진");
	}

	@Test
	@DisplayName("승격 멱등 — 같은 장르 반영이 재전달돼도 전시가 중복 생성되지 않는다(재전달 no-op)")
	void promote_재전달_멱등() {
		int seq = SEQ.getAndIncrement();
		String externalId = "IDEM-" + seq;
		LocalDateTime now = LocalDateTime.now();
		exhibitionDraftFacade.stageFromList(listData(externalId, "멱등장소" + seq), now);
		exhibitionDraftFacade.markDetailAbsent(externalId, now); // 무상세 해소도 게이트를 채운다

		exhibitionDraftFacade.applyGenre(externalId,
				GenreResult.ai("사진", GenreProvider.GEMINI, "gemini-2.5-flash"), now);
		exhibitionDraftFacade.applyGenre(externalId,
				GenreResult.ai("공예", GenreProvider.CLAUDE, "claude-haiku-4-5-20251001"), now); // 재전달

		exhibitionDraftFacade.completePromotion(externalId, now); // 소비
		exhibitionDraftFacade.completePromotion(externalId, now); // 소비 재전달 — no-op(멱등)

		Exhibition promoted = exhibitionRepository.findByExternalId(externalId).orElseThrow();
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElseThrow();
		assertThat(draft.getStatus()).isEqualTo(DraftStatus.COMPLETED);
		assertThat(draft.getGenreKeyword()).isEqualTo("사진"); // 첫 반영이 이긴다(재전달은 no-op)
		assertThat(promoted.getId()).isEqualTo(draft.getPromotedExhibitionId());
	}
}
