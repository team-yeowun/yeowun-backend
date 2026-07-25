package modi.backend.ingestion.application.progress;

import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.ingestion.application.outbox.OutboxPublisher;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.progress.ExhibitionProgress;
import modi.backend.ingestion.domain.progress.ExhibitionProgressRepository;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * 진행 상태 머신의 서비스(구 ExhibitionDraftService) — 스테이징·스텝 마커·승격 게이트, 그리고 <b>이벤트 원자
 * 발행의 단독 소유자</b>다(설계 §3-4). 외부 호출은 일절 없다 — 호출(상세·AI·구글)은 각 축 서비스가, 이벤트 소비
 * 순서는 {@code ExhibitionIngestionOrchestrator}가 안다.
 *
 * <p><b>이벤트 4종 전부 여기서 발행된다</b>(설계 §4): 스테이징이 {@code DRAFT_STAGED}+{@code PLACE_STAGED}를,
 * 상세 해소가 {@code DETAIL_FETCHED}를, 게이트 충족이 {@code DRAFT_READY}를 <b>같은 트랜잭션</b>에서 기록한다
 * (아웃박스 원자성 — 상태는 반영됐는데 사실 기록이 유실되는 창이 없다). 발행은 {@link OutboxPublisher}
 * 컴포넌트 경유다(의존 규칙 §1-1 — 서비스→서비스 금지).
 *
 * <p><b>원장 합류</b>: 값(목록·상세·장르)은 진행 행이 아니라 원장(스냅샷)이 갖는다 — {@link SnapshotLedger}의
 * REQUIRED 메서드가 각 반영 tx에 합류해 [원장 + 마커 + 이벤트]가 원자다(마커⇒원장 불변식).
 *
 * <p>상태 변경은 전부 {@link ExhibitionProgress} 메서드 안에서만 일어난다(서비스는 load·조율·save).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionProgressService {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionProgressService.class);

	private final ExhibitionProgressRepository progressRepository;
	/** 원장 쓰기면 — 반영 tx에 합류(REQUIRED). */
	private final SnapshotLedger ledger;
	/** 원장 읽기면 — 승격 시 등록 입력 조립. */
	private final ExhibitionAssembler assembler;
	/** 이벤트 발행 — 아웃박스 행 INSERT가 상태 반영과 같은 tx. */
	private final OutboxPublisher publisher;
	/** 코어 등록 계약 — 승격 소비 시 전시+부속 생성의 유일한 통로(ADR-12). */
	private final ExhibitionRegistrar exhibitionRegistrar;
	/** 스테이징 1건의 트랜잭션 경계 — 단건 실패를 경계 <b>밖</b>에서 삼키려면 경계가 명시적이어야 한다. */
	private final TransactionTemplate transactionTemplate;

	/** 스테이징 결과 — 동기화 루프의 집계 어휘. */
	public enum StageOutcome {
		/** 새 진행 행이 만들어졌다(+ DRAFT_STAGED·PLACE_STAGED 발행). */
		STAGED,
		/** 기존 미종료 행 — 원장 갱신 + 미해소 스텝 이벤트 보강. */
		REFRESHED,
		/** 종료(COMPLETED/FAILED) 행 — 원장만 갱신하고 손대지 않았다. */
		SKIPPED,
		/** 이 한 건이 실패해 다음 회차로 미뤘다(배치는 계속된다). */
		DEFERRED
	}

	/**
	 * 목록 1건을 스테이징한다 — [목록 원장 upsert + 진행 행 + DRAFT_STAGED·PLACE_STAGED 발행]이 <b>한
	 * 트랜잭션</b>(원장 합류 — "스냅샷 있음·진행 없음" 반쪽이 생기지 않아 목록 조기 종료 판정도 안전하다).
	 */
	public StageOutcome stageFromList(CatalogExhibitionData data, LocalDateTime syncedAt) {
		try {
			// 트랜잭션 경계를 명시적으로 연다 — catch가 경계 밖이어야 한다. @Transactional 메서드 안에서 삼키면
			// 롤백 표시된 트랜잭션을 커밋하려다 터지거나(UnexpectedRollbackException) 반쪽 상태가 커밋된다.
			return transactionTemplate.execute(status -> stageOne(data, syncedAt));
		} catch (RuntimeException e) {
			// 한 행의 실패가 배치 전체를 죽이지 않는다 — 이 판단은 스테이징의 지식이라 여기서 삼키고 결과로 알린다.
			log.warn("전시 스테이징 단건 실패(externalId={}, 다음 주기 재시도): {}", data.externalId(), e.getMessage());
			return StageOutcome.DEFERRED;
		}
	}

	private StageOutcome stageOne(CatalogExhibitionData data, LocalDateTime syncedAt) {
		ledger.recordList(data, syncedAt); // 원장이 먼저·같은 tx — 실패하면 전체 롤백(다음 회차가 다시 만난다).
		if (!data.hasValidPeriod()) {
			// 기간 불량(종료<시작)은 파이프라인 대상이 아니다 — 단, 벤더 원장은 도메인 유효성과 무관하게 남는다
			// (ADR-13 — 원본 기록·목록 조기 종료 판정의 재료). 진행 행·이벤트는 만들지 않는다.
			return StageOutcome.SKIPPED;
		}
		ExhibitionProgress existing = progressRepository.findByExternalId(data.externalId()).orElse(null);
		if (existing == null) {
			ExhibitionProgress staged = progressRepository.save(ExhibitionProgress.stage(data));
			publisher.enqueue(IngestionEventType.DRAFT_STAGED, staged.getExternalId(), syncedAt);
			// 전시장 축은 place_key 단위 dedup(UK) — 같은 장소 전시 N건이어도 이벤트 1건(유료 호출 장소당 1번).
			publisher.enqueue(IngestionEventType.PLACE_STAGED, staged.getPlaceKey(), syncedAt);
			return StageOutcome.STAGED;
		}
		if (existing.getStatus().isTerminal()) {
			// 재스테이징 가드 — 승격된 전시는 반드시 COMPLETED 행을 남기므로 이 검사가 파이프라인 중복 가동을 막는다.
			return StageOutcome.SKIPPED;
		}
		existing.refreshPlaceKey(data);
		progressRepository.save(existing);
		// 미해소 스텝의 메시지가 사라졌거나 종료로 굳었어도 재sync 안전망이 복원·부활시킨다(ADR-12 보강).
		switch (existing.nextStep()) {
			case FETCH_DETAIL -> publisher.enqueueOrReactivate(IngestionEventType.DRAFT_STAGED,
					existing.getExternalId(), syncedAt);
			case CLASSIFY_GENRE -> publisher.enqueueOrReactivate(IngestionEventType.DETAIL_FETCHED,
					existing.getExternalId(), syncedAt);
			case PROMOTE -> publisher.enqueueOrReactivate(IngestionEventType.DRAFT_READY,
					existing.getExternalId(), syncedAt);
			case NONE -> { /* 종료 직전 경합 또는 목록 코어 불완전 — 다음 sync가 판단한다 */ }
		}
		return StageOutcome.REFRESHED;
	}

	/** 상세 스텝(DRAFT_STAGED 소비) 핸들러의 ① 판정 — 상세 스텝이 미해소인 진행 행이 있는가. */
	@Transactional(readOnly = true)
	public boolean needsDetail(String externalId) {
		return progressRepository.findByExternalId(externalId)
				.map(ExhibitionProgress::needsDetail)
				.orElse(false);
	}

	/** 장르 스텝(DETAIL_FETCHED 소비) 핸들러의 ① 판정 — 장르 스텝이 미해소인 진행 행이 있는가. */
	@Transactional(readOnly = true)
	public boolean needsGenre(String externalId) {
		return progressRepository.findByExternalId(externalId)
				.map(ExhibitionProgress::needsGenre)
				.orElse(false);
	}

	/**
	 * 상세 값 도착(상세 스텝 해소) — [상세 원장 upsert + 마커 + DETAIL_FETCHED 발행 (+게이트 충족 시
	 * DRAFT_READY)]이 한 트랜잭션. 원문 보관이 반영과 같은 tx에 합류한다(원장화 — 구 best-effort 폐기).
	 */
	@Transactional
	public void applyDetail(String externalId, CultureDetailPayload payload, LocalDateTime now) {
		ExhibitionProgress progress = progressRepository.findByExternalId(externalId).orElse(null);
		if (progress == null || !progress.needsDetail()) {
			return; // 재전달·경합 — 이미 해소됐거나 대상이 아니다.
		}
		ledger.recordDetail(externalId, payload);
		progress.markDetailResolved(now);
		publisher.enqueue(IngestionEventType.DETAIL_FETCHED, externalId, now);
		enqueueReadyIfGateFilled(progress, now);
		progressRepository.save(progress);
	}

	/** 원천 무상세 확인(상세 스텝 해소) — 원장 없이 마커만 해소하고 같은 사실(DETAIL_FETCHED)을 발행한다. */
	@Transactional
	public void markDetailAbsent(String externalId, LocalDateTime now) {
		ExhibitionProgress progress = progressRepository.findByExternalId(externalId).orElse(null);
		if (progress == null || !progress.needsDetail()) {
			return;
		}
		progress.markDetailResolved(now);
		publisher.enqueue(IngestionEventType.DETAIL_FETCHED, externalId, now);
		enqueueReadyIfGateFilled(progress, now);
		progressRepository.save(progress);
	}

	/** 장르 반영(장르 스텝 해소) — [장르 원장 upsert + 마커 (+DRAFT_READY)]이 한 트랜잭션. */
	@Transactional
	public void applyGenre(String externalId, GenreResult result, LocalDateTime now) {
		ExhibitionProgress progress = progressRepository.findByExternalId(externalId).orElse(null);
		if (progress == null || !progress.needsGenre()) {
			return; // 재전달·경합 — 이미 분류됐거나 대상이 아니다.
		}
		ledger.recordGenre(externalId, result, now);
		progress.markGenreClassified(now);
		enqueueReadyIfGateFilled(progress, now);
		progressRepository.save(progress);
	}

	/**
	 * <b>모든 스텝 해소 지점</b>에서 게이트를 검사한다 — "마지막 스텝 = 장르" 순서 가정에 기대지 않는다.
	 * 게이트를 채웠으면 그 스텝의 트랜잭션이 {@code DRAFT_READY}를 <b>원자 기록</b>한다 — 스텝은 반영됐는데
	 * 승격 신호가 유실되는 창이 없다("검사 시점엔 미충족, 1초 뒤 충족" 경합은 마지막 스텝 tx가 스스로 쏘므로 없다).
	 */
	private void enqueueReadyIfGateFilled(ExhibitionProgress progress, LocalDateTime now) {
		if (progress.isReadyForPromotion()) {
			publisher.enqueue(IngestionEventType.DRAFT_READY, progress.getExternalId(), now);
		}
	}

	/**
	 * 승격 소비(DRAFT_READY 해소) — [진행 재조회·게이트 재검사 → 원장 3종 어셈블 → 코어 등록(멱등) → 진행
	 * 종료]가 한 트랜잭션. 재전달·경합이면(종료됐거나 게이트 미충족) no-op. 등록 자체는
	 * {@code exhibitions.external_id} UK가 최후의 멱등 가드라, 소비가 몇 번 반복돼도 전시는 한 번만 생긴다.
	 */
	@Transactional
	public void completePromotion(String externalId, LocalDateTime now) {
		ExhibitionProgress progress = progressRepository.findByExternalId(externalId).orElse(null);
		if (progress == null || progress.getStatus().isTerminal() || !progress.isReadyForPromotion()) {
			return; // 재전달·경합 — 이미 승격됐거나 아직 게이트 미충족(잔존 메시지).
		}
		ExhibitionRegistrar.Registered registered = exhibitionRegistrar.register(assembler.assemble(externalId), now);
		progress.complete(registered.exhibitionId(), now);
		progressRepository.save(progress);
		log.info("전시 진행 승격(externalId={} → exhibitionId={})", externalId, registered.exhibitionId());
	}

	/** 필수 스텝의 영구 실패(4xx·시도 소진 — D5로 장르 포함 전 스텝) — FAILED로 종료해 관리자에게 보인다. */
	@Transactional
	public void markStepPermanentlyFailed(String externalId, String error, LocalDateTime now) {
		ExhibitionProgress progress = progressRepository.findByExternalId(externalId).orElse(null);
		if (progress == null || progress.getStatus().isTerminal()) {
			return;
		}
		progress.fail(error, now);
		progressRepository.save(progress);
		log.warn("전시 진행 영구 실패(externalId={}): {}", externalId, error);
	}

	// ── 전시장 축(PLACE_STAGED 소비) 협력 ─────────────────────────────────────────

	/** 그 전시장을 기다리는 진행 행의 원천키 하나 — 전시장 축 시드 해소의 출발점(없으면 대상 소멸 = 성공 마감). */
	@Transactional(readOnly = true)
	public Optional<String> findAnyExternalIdByPlaceKey(String placeKey) {
		return progressRepository.findAllByPlaceKey(placeKey).stream()
				.map(ExhibitionProgress::getExternalId)
				.findFirst();
	}

	/** 전시장 축 분기 결과(새/기존)를 같은 place_key의 진행 행들에 마크한다 — 대시보드 §7의 소스. */
	@Transactional
	public void markPlaceOutcome(String placeKey, boolean created, LocalDateTime now) {
		for (ExhibitionProgress progress : progressRepository.findAllByPlaceKey(placeKey)) {
			progress.markPlaceOutcome(created);
			progressRepository.save(progress);
		}
	}
}
