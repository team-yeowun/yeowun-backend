package modi.backend.ingestion.application.draft;

import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.draft.ExhibitionDraft;
import modi.backend.ingestion.domain.draft.ExhibitionDraftRepository;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.outbox.IngestionEventType;

/**
 * draft 상태 머신의 서비스(ADR-10 2부) — 스테이징·스텝 해소·승격 게이트, draft의 DB 경계를 맡는다(설계 §5).
 * 외부 호출은 일절 없다 — 호출(상세·AI)은 각 축 서비스가, 이벤트 소비 순서는 {@code ExhibitionIngestionOrchestrator}가 안다.
 *
 * <p><b>이벤트 원자 발행</b>: 스테이징이 {@code DRAFT_STAGED}를, 상세 해소가 {@code DETAIL_FETCHED}를
 * <b>같은 트랜잭션</b>에서 기록한다(아웃박스 원자성 — 스텝은 반영됐는데 사실 기록이 유실되는 창이 없다).
 * 기록되는 건 "이 서비스가 한 일"이지 다음 할 일이 아니다(설계 §1) — 그 다음이 무엇인지는 Facade 매핑만 안다.
 *
 * <p><b>승격 = 원자 발행 + 멱등 소비(ADR-12)</b>: 게이트를 채운 마지막 스텝의 트랜잭션은 코어를 직접 쓰지 않고
 * {@code DRAFT_READY} 이벤트를 <b>원자 기록</b>한다. 소비({@link #completePromotion})는 별도 트랜잭션에서
 * [draft 재조회 → 코어 등록 계약({@link ExhibitionRegistrar}, {@code external_id} UK 멱등) → 영업시간 재검증
 * enqueue → draft 종료]를 완주한다. 코어 리포 직주입 없음 — 수집이 코어에 닿는 통로는 등록 계약뿐이다.
 *
 * <p>상태 변경은 전부 {@link ExhibitionDraft}·애그리거트 루트 메서드 안에서만 일어난다(서비스는 load·조율·save).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionDraftService {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionDraftService.class);

	private final ExhibitionDraftRepository exhibitionDraftRepository;
	/** 코어 등록 계약 — 승격 소비 시 전시+부속 생성의 유일한 통로(ADR-12). */
	private final ExhibitionRegistrar exhibitionRegistrar;
	/** 전시 아웃박스 — 이벤트 원자 발행(DRAFT_STAGED·DETAIL_FETCHED·DRAFT_READY)·승격 후 영업시간 재검증 enqueue. */
	private final ExhibitionOutboxService exhibitionOutboxService;
	/** 스테이징 1건의 트랜잭션 경계 — 단건 실패를 경계 <b>밖</b>에서 삼키려면 경계가 명시적이어야 한다. */
	private final TransactionTemplate transactionTemplate;

	/** 스테이징 결과 — 동기화 루프의 집계 어휘. */
	public enum StageOutcome {
		/** 새 draft가 만들어졌다(+ DRAFT_STAGED 발행). */
		STAGED,
		/** 기존 미종료 draft의 목록분을 갱신했다. */
		REFRESHED,
		/** 종료(COMPLETED/FAILED) draft — 손대지 않았다. */
		SKIPPED,
		/** 이 한 건이 실패해 다음 회차로 미뤘다(배치는 계속된다). */
		DEFERRED
	}

	/**
	 * 목록 1건을 스테이징한다 — [draft 저장 + DRAFT_STAGED 발행]이 <b>한 트랜잭션</b>(ADR-10 원자성).
	 * 재sync가 같은 원천을 다시 만나면 목록분만 갱신하고, 미해소 스텝의 이벤트는 멱등 enqueue로 보강한다.
	 */
	public StageOutcome stageFromList(CatalogExhibitionData data, LocalDateTime now) {
		try {
			// 트랜잭션 경계를 명시적으로 연다 — catch가 경계 <b>밖</b>이어야 한다. @Transactional 메서드 안에서 삼키면
			// 롤백 표시된 트랜잭션을 커밋하려다 터지거나(UnexpectedRollbackException) 반쪽 상태가 커밋된다.
			return transactionTemplate.execute(status -> stageOne(data, now));
		} catch (RuntimeException e) {
			// 한 행의 실패가 배치 전체를 죽이지 않는다 — 이 판단은 스테이징의 지식이라 여기서 삼키고 결과로 알린다
			// (호출부는 집계만 한다). 다음 회차가 같은 행을 다시 만나 재시도한다.
			log.warn("전시 스테이징 단건 실패(externalId={}, 다음 주기 재시도): {}", data.externalId(), e.getMessage());
			return StageOutcome.DEFERRED;
		}
	}

	private StageOutcome stageOne(CatalogExhibitionData data, LocalDateTime now) {
		ExhibitionDraft existing = exhibitionDraftRepository.findByExternalId(data.externalId()).orElse(null);
		if (existing == null) {
			ExhibitionDraft staged = exhibitionDraftRepository.save(ExhibitionDraft.stage(data));
			exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, staged.getExternalId(), now);
			return StageOutcome.STAGED;
		}
		if (existing.getStatus().isTerminal()) {
			// 재스테이징 가드 — 승격된 전시는 반드시 COMPLETED draft를 남기므로(draft 단일 경로) 이 검사가
			// 파이프라인 중복 가동(AI 콜 낭비 + 멱등 등록 no-op)을 막는다. 종료 draft는 불변 —
			// 승격됐거나(전시 존재) 영구 실패(수동 개입 대상).
			return StageOutcome.SKIPPED;
		}
		existing.refreshFromList(data);
		exhibitionDraftRepository.save(existing);
		// 미해소 스텝의 메시지가 사라졌거나 <b>종료로 굳었어도</b> 재sync 안전망이 복원·부활시킨다(ADR-12 보강 —
		// 게이트 일시 해제 창의 no-op 소비, 실패 전이 후 크래시 같은 드문 창에서도 draft가 영구 침묵하지 않는다).
		switch (existing.nextStep()) {
			case FETCH_DETAIL -> exhibitionOutboxService.enqueueOrReactivate(IngestionEventType.DRAFT_STAGED,
					existing.getExternalId(), now);
			case CLASSIFY_GENRE -> exhibitionOutboxService.enqueueOrReactivate(IngestionEventType.DETAIL_FETCHED,
					existing.getExternalId(), now);
			// 게이트는 다 찼는데 종료 전인 draft — 잃어버린 승격 신호를 복원한다(ADR-12).
			case PROMOTE -> exhibitionOutboxService.enqueueOrReactivate(IngestionEventType.DRAFT_READY,
					existing.getExternalId(), now);
			case NONE -> { /* 목록 코어 불완전 — 다음 sync가 채운다 */ }
		}
		return StageOutcome.REFRESHED;
	}

	/** 상세 스텝(DRAFT_STAGED 소비) 핸들러의 draft 경로 판정 — 상세 스텝이 미해소인 draft가 있는가. */
	@Transactional(readOnly = true)
	public boolean needsDetail(String externalId) {
		return exhibitionDraftRepository.findByExternalId(externalId)
				.map(ExhibitionDraft::needsDetail)
				.orElse(false);
	}

	/** 장르 스텝(DETAIL_FETCHED 소비) 핸들러의 draft 경로 판정 — 장르 스텝이 미해소인 draft의 분류 입력을 돌려준다(아니면 empty). */
	@Transactional(readOnly = true)
	public Optional<GenreClassification> resolveGenreInput(String externalId) {
		return exhibitionDraftRepository.findByExternalId(externalId)
				.filter(ExhibitionDraft::needsGenre)
				.map(draft -> new GenreClassification(draft.getTitle(),
						draft.getCategory() == null ? null : draft.getCategory().name(),
						draft.getDescription(), draft.getPlaceName(), null, draft.getRealmName()));
	}

	/**
	 * 상세 값 도착(상세 스텝 해소) — [draft 상세분 반영 + DETAIL_FETCHED 발행]이 한 트랜잭션. 벤더 원본 보관은
	 * 여기가 아니라 호출 직후 best-effort로 끝났다(행위 변경 — 원문은 독립 사실이라 draft 반영 실패에도 남는다).
	 * 다음 필수 스텝(장르)이 상세 도착 <b>후에</b> 걸리는 이유: 분류 입력(설명·장소)이 그때 온전해진다.
	 */
	@Transactional
	public void applyDetail(String externalId, CultureDetailPayload payload, LocalDateTime now) {
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElse(null);
		if (draft == null || !draft.needsDetail()) {
			return; // 재전달·경합 — 이미 해소됐거나 대상이 아니다.
		}
		draft.applyDetail(payload.toDetail(), now);
		exhibitionOutboxService.enqueue(IngestionEventType.DETAIL_FETCHED, externalId, now);
		enqueueReadyIfGateFilled(draft, now);
		exhibitionDraftRepository.save(draft);
	}

	/** 원천 무상세 확인(상세 스텝 해소) — 값 없이 스텝만 해소하고 같은 사실(DETAIL_FETCHED)을 발행한다. */
	@Transactional
	public void markDetailAbsent(String externalId, LocalDateTime now) {
		ExhibitionDraft draft = exhibitionDraftRepository.findByExternalId(externalId).orElse(null);
		if (draft == null || !draft.needsDetail()) {
			return;
		}
		draft.markDetailAbsent(now);
		exhibitionOutboxService.enqueue(IngestionEventType.DETAIL_FETCHED, externalId, now);
		enqueueReadyIfGateFilled(draft, now);
		exhibitionDraftRepository.save(draft);
	}

	/** 장르 반영(장르 스텝 해소) — 정상 체인에선 장르가 마지막 필수 스텝이라 대개 여기서 승격 발행이 걸린다. */
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
	 * 게이트를 채웠으면 그 스텝의 트랜잭션이 {@code DRAFT_READY}를 <b>원자 기록</b>한다(ADR-12) — 스텝은
	 * 반영됐는데 승격 신호가 유실되는 창이 없다. 멱등 enqueue라 여러 지점에서 반복 검사해도 안전하다.
	 */
	private void enqueueReadyIfGateFilled(ExhibitionDraft draft, LocalDateTime now) {
		if (draft.isReadyForPromotion()) {
			exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_READY, draft.getExternalId(), now);
		}
	}

	/**
	 * 승격 소비(DRAFT_READY 해소, ADR-12) — [draft 재조회 → 코어 등록(멱등) → 영업시간 재검증 enqueue →
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
		exhibitionOutboxService.enqueueHoursRefresh(registered.placeKey(), now);
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
}