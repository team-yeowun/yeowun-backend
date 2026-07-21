package modi.backend.ingestion.application.draft;

import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.draft.ExhibitionDraft;
import modi.backend.ingestion.domain.draft.ExhibitionDraftRepository;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.entity.CultureDetailSnapshot;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.ingestion.infra.CultureDetailSnapshotJpaRepository;

/**
 * 전시 초기화 스테이징·승격 유스케이스 조율(ADR-10 2부) — draft의 DB 경계를 맡는다.
 *
 * <p><b>스텝 체인</b>: 스테이징이 FETCH_DETAIL을, 상세 해소가 CLASSIFY_GENRE를 <b>같은 트랜잭션</b>에서 enqueue한다
 * (아웃박스 원자성 — 스텝은 반영됐는데 다음 스텝 메시지가 유실되는 창이 없다).
 *
 * <p><b>승격 = 원자 발행 + 멱등 소비(ADR-12)</b>: 게이트를 채운 마지막 스텝의 트랜잭션은 코어를 직접 쓰지 않고
 * {@code EXHIBITION_READY} 메시지를 <b>원자 기록</b>한다. 소비({@link #completePromotion})는 별도 트랜잭션에서
 * [draft 재조회 → 코어 등록 계약({@link ExhibitionRegistrar}, {@code external_id} UK 멱등) → 영업시간 재검증
 * enqueue → draft 종료]를 완주한다. 코어 리포 직주입 없음 — 수집이 코어에 닿는 통로는 등록 계약뿐이다.
 *
 * <p>상태 변경은 전부 {@link ExhibitionDraft}·애그리거트 루트 메서드 안에서만 일어난다(Facade는 load·조율·save).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionDraftFacade {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionDraftFacade.class);

	private final ExhibitionDraftRepository exhibitionDraftRepository;
	/** 코어 등록 계약 — 승격 소비 시 전시+부속 생성의 유일한 통로(ADR-12). */
	private final ExhibitionRegistrar exhibitionRegistrar;
	/** 전시 아웃박스 — 스텝 체인(FETCH_DETAIL→CLASSIFY_GENRE→EXHIBITION_READY)·승격 후 영업시간 재검증 enqueue. */
	private final ExhibitionOutboxFacade exhibitionOutboxFacade;
	/** 상세 벤더 스냅샷 — 응답 원문을 그대로 적재한다(도메인 계약 경유). */
	private final CultureDetailSnapshotJpaRepository cultureDetailSnapshotRepository;

	/** 스테이징 결과 — 동기화 루프의 집계 어휘. */
	public enum StageOutcome {
		/** 새 draft가 만들어졌다(+ FETCH_DETAIL enqueue). */
		STAGED,
		/** 기존 미종료 draft의 목록분을 갱신했다. */
		REFRESHED,
		/** 종료(COMPLETED/FAILED) draft — 손대지 않았다. */
		SKIPPED
	}

	/**
	 * 목록 1건을 스테이징한다 — [draft 저장 + FETCH_DETAIL enqueue]가 <b>한 트랜잭션</b>(ADR-10 원자성).
	 * 재sync가 같은 원천을 다시 만나면 목록분만 갱신하고, 미해소 스텝의 메시지는 멱등 enqueue로 보강한다.
	 */
	@Transactional
	public StageOutcome stageFromList(CatalogExhibitionData data, LocalDateTime now) {
		ExhibitionDraft existing = exhibitionDraftRepository.findByExternalId(data.externalId()).orElse(null);
		if (existing == null) {
			ExhibitionDraft staged = exhibitionDraftRepository.save(ExhibitionDraft.stage(data));
			exhibitionOutboxFacade.enqueue(OutboxMessageType.FETCH_DETAIL, staged.getExternalId(), now);
			return StageOutcome.STAGED;
		}
		if (existing.getStatus().isTerminal()) {
			return StageOutcome.SKIPPED; // 종료 draft는 불변 — 승격됐거나(전시 존재) 영구 실패(수동 개입 대상).
		}
		existing.refreshFromList(data);
		exhibitionDraftRepository.save(existing);
		// 미해소 스텝의 메시지가 사라졌거나 <b>종료로 굳었어도</b> 재sync 안전망이 복원·부활시킨다(ADR-12 보강 —
		// 게이트 일시 해제 창의 no-op 소비, 실패 전이 후 크래시 같은 드문 창에서도 draft가 영구 침묵하지 않는다).
		switch (existing.nextStep()) {
			case FETCH_DETAIL -> exhibitionOutboxFacade.enqueueOrReactivate(OutboxMessageType.FETCH_DETAIL,
					existing.getExternalId(), now);
			case CLASSIFY_GENRE -> exhibitionOutboxFacade.enqueueOrReactivate(OutboxMessageType.CLASSIFY_GENRE,
					existing.getExternalId(), now);
			// 게이트는 다 찼는데 종료 전인 draft — 잃어버린 승격 신호를 복원한다(ADR-12).
			case PROMOTE -> exhibitionOutboxFacade.enqueueOrReactivate(OutboxMessageType.EXHIBITION_READY,
					existing.getExternalId(), now);
			case NONE -> { /* 목록 코어 불완전 — 다음 sync가 채운다 */ }
		}
		return StageOutcome.REFRESHED;
	}

	/** FETCH_DETAIL 핸들러의 draft 경로 판정 — 상세 스텝이 미해소인 draft가 있는가. */
	@Transactional(readOnly = true)
	public boolean needsDetail(String externalId) {
		return exhibitionDraftRepository.findByExternalId(externalId)
				.map(ExhibitionDraft::needsDetail)
				.orElse(false);
	}

	/** 미종료 draft가 있는가 — 재전달 메시지가 승격 전 draft를 "대상 미존재"로 오판하지 않기 위한 판정. */
	@Transactional(readOnly = true)
	public boolean hasActiveDraft(String externalId) {
		return exhibitionDraftRepository.findByExternalId(externalId)
				.map(draft -> !draft.getStatus().isTerminal())
				.orElse(false);
	}

	/** CLASSIFY_GENRE 핸들러의 draft 경로 판정 — 장르 스텝이 미해소인 draft의 분류 입력을 돌려준다(아니면 empty). */
	@Transactional(readOnly = true)
	public Optional<GenreClassification> resolveGenreInput(String externalId) {
		return exhibitionDraftRepository.findByExternalId(externalId)
				.filter(ExhibitionDraft::needsGenre)
				.map(draft -> new GenreClassification(draft.getTitle(),
						draft.getCategory() == null ? null : draft.getCategory().name(),
						draft.getDescription(), draft.getPlaceName(), null, draft.getRealmName()));
	}

	/**
	 * 상세 값 도착(FETCH_DETAIL 해소) — [draft 상세분 반영 + 벤더 원본 적재 + CLASSIFY_GENRE enqueue]가 한 트랜잭션.
	 * 다음 필수 스텝(장르)이 상세 도착 <b>후에</b> 걸리는 이유: 분류 입력(설명·장소)이 그때 온전해진다(스텝 체인).
	 */
	@Transactional
	public void applyDetail(String externalId, CultureDetailPayload payload, LocalDateTime now) {
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElse(null);
		if (draft == null || !draft.needsDetail()) {
			return; // 재전달·경합 — 이미 해소됐거나 대상이 아니다.
		}
		draft.applyDetail(payload.toDetail(), now);
		archiveDetailSnapshot(externalId, payload);
		exhibitionOutboxFacade.enqueue(OutboxMessageType.CLASSIFY_GENRE, externalId, now);
		enqueueReadyIfGateFilled(draft, now);
		exhibitionDraftRepository.save(draft);
	}

	/** 원천 무상세 확인(FETCH_DETAIL 해소) — 값 없이 스텝만 해소하고 다음 스텝(장르)을 건다. */
	@Transactional
	public void markDetailAbsent(String externalId, LocalDateTime now) {
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElse(null);
		if (draft == null || !draft.needsDetail()) {
			return;
		}
		draft.markDetailAbsent(now);
		exhibitionOutboxFacade.enqueue(OutboxMessageType.CLASSIFY_GENRE, externalId, now);
		enqueueReadyIfGateFilled(draft, now);
		exhibitionDraftRepository.save(draft);
	}

	/** 장르 반영(CLASSIFY_GENRE 해소) — 정상 체인에선 장르가 마지막 필수 스텝이라 대개 여기서 승격 발행이 걸린다. */
	@Transactional
	public void applyGenre(String externalId, GenreResult result, LocalDateTime now) {
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElse(null);
		if (draft == null || !draft.needsGenre()) {
			return; // 재전달·경합 — 이미 분류됐거나 대상이 아니다.
		}
		draft.applyGenre(result, now);
		enqueueReadyIfGateFilled(draft, now);
		exhibitionDraftRepository.save(draft);
	}

	/**
	 * <b>모든 스텝 해소 지점</b>에서 게이트를 검사한다 — "마지막 스텝 = 장르" 순서 가정에 기대지 않는다(ADR-10 보강).
	 * 게이트를 채웠으면 그 스텝의 트랜잭션이 {@code EXHIBITION_READY}를 <b>원자 기록</b>한다(ADR-12) — 스텝은
	 * 반영됐는데 승격 신호가 유실되는 창이 없다. 멱등 enqueue라 여러 지점에서 반복 검사해도 안전하다.
	 */
	private void enqueueReadyIfGateFilled(ExhibitionDraft draft, LocalDateTime now) {
		if (draft.isReadyForPromotion()) {
			exhibitionOutboxFacade.enqueue(OutboxMessageType.EXHIBITION_READY, draft.getExternalId(), now);
		}
	}

	/**
	 * 승격 소비(EXHIBITION_READY 해소, ADR-12) — [draft 재조회 → 코어 등록(멱등) → 영업시간 재검증 enqueue →
	 * draft 종료]가 한 트랜잭션. 재전달·경합이면(종료됐거나 게이트 미충족) no-op. 등록 자체는
	 * {@code exhibitions.external_id} UK가 최후의 멱등 가드라, 소비가 몇 번 반복돼도 전시는 한 번만 생긴다.
	 */
	@Transactional
	public void completePromotion(String externalId, LocalDateTime now) {
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElse(null);
		if (draft == null || draft.getStatus().isTerminal() || !draft.isReadyForPromotion()) {
			return; // 재전달·경합 — 이미 승격됐거나 아직 게이트 미충족(잔존 메시지).
		}
		ExhibitionRegistrar.Registered registered = exhibitionRegistrar.register(toRegistration(draft), now);
		// 이벤트 구동 재검증(설계 §4-1): 새 전시가 기존 장소에 들어오면 재검증을 건다 — 같은 트랜잭션(원자성).
		exhibitionOutboxFacade.enqueueHoursRefresh(registered.placeKey(), now);
		draft.complete(registered.exhibitionId(), now);
		exhibitionDraftRepository.save(draft);
		log.info("전시 draft 승격(externalId={} → exhibitionId={})", externalId, registered.exhibitionId());
	}

	/** 필수 스텝의 영구 실패(4xx·시도 소진) — draft를 FAILED로 종료해 운영자에게 보인다. */
	@Transactional
	public void markStepPermanentlyFailed(String externalId, String error, LocalDateTime now) {
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElse(null);
		if (draft == null || draft.getStatus().isTerminal()) {
			return;
		}
		draft.fail(error, now);
		exhibitionDraftRepository.save(draft);
		log.warn("전시 draft 영구 실패(externalId={}): {}", externalId, error);
	}

	/** 완성 draft의 필드 스냅샷 → 코어 등록 입력(코어 소유 어휘 — 수집 타입이 경계를 넘지 않는다). */
	private ExhibitionRegistration toRegistration(ExhibitionDraft d) {
		return new ExhibitionRegistration(d.getExternalId(), d.getTitle(), d.getPlaceName(), d.getRegion(),
				d.getSigungu(), d.getGpsX(), d.getGpsY(), d.getStartDate(), d.getEndDate(), d.getCategory(),
				d.getPosterUrl(), d.getDetailUrl(), d.getServiceName(), d.getPrice(), d.getDescription(),
				d.getImgUrl(), d.getPlaceAddr(), d.getPlacePhone(), d.getPlaceUrl(), d.getGenreKeyword(),
				d.getGenreProvider(), d.getGenreModel());
	}

	/** 상세 스냅샷 upsert(응답 원문 그대로 — ADR-13). 부가 기록이라 실패해도 반영을 깨지 않는다. */
	private void archiveDetailSnapshot(String externalId, CultureDetailPayload payload) {
		try {
			cultureDetailSnapshotRepository.findByExternalId(externalId)
					.ifPresentOrElse(row -> {
						row.refresh(payload);
						cultureDetailSnapshotRepository.save(row);
					}, () -> cultureDetailSnapshotRepository.save(
							CultureDetailSnapshot.first(externalId, payload)));
		} catch (RuntimeException e) {
			log.warn("상세 스냅샷 적재 실패(externalId={}, 반영은 계속): {}", externalId, e.getMessage());
		}
	}
}