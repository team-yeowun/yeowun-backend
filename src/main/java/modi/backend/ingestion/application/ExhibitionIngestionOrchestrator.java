package modi.backend.ingestion.application;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.application.exhibition.contract.PlaceRegistrar;
import modi.backend.ingestion.application.audit.IngestionRunRecorder;
import modi.backend.ingestion.application.culture.ExhibitionKoreaCultureService;
import modi.backend.ingestion.application.genre.ExhibitionAiGenreService;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.application.outbox.StepResult;
import modi.backend.ingestion.application.place.ExhibitionPlaceService;
import modi.backend.ingestion.application.progress.ExhibitionProgressService;
import modi.backend.ingestion.domain.SyncTrigger;
import modi.backend.ingestion.domain.audit.IngestionRun;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.ingestion.application.progress.ExhibitionProgressService.StageOutcome;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;

/**
 * 전시 수집 파이프라인의 <b>오케스트레이션 전담 조합자</b>(설계 §2) — application 루트에 단 하나다.
 * "흐름이 궁금하면 이 파일 하나"가 이 구조의 가독성 계약이다. 소유하는 것은 둘뿐이다:
 * <ol>
 *   <li><b>수집 진입점</b>({@link #syncCatalog}): 한 회차에 할 일을 찾아 큐에 싣는 유일한 메서드 —
 *       목록 수집·스테이징(행마다 [목록 원장 + 진행 행 + DRAFT_STAGED·PLACE_STAGED] 원자)이 전부다.
 *       영업시간 스윕은 없다(설계 D4 — 재검증 폐기).</li>
 *   <li><b>이벤트 소비</b>: 릴레이가 도래 이벤트를 집어오면 <b>이벤트 타입 → 다음 스텝</b> 매핑을 실행한다.
 *       스텝 순서 지식은 이 매핑 한 곳에만 있다(서비스는 자기 사실만 발행한다):
 *       <pre>
 *       DRAFT_STAGED   → 상세 3박자({@link #detailStep})
 *       DETAIL_FETCHED → 장르 3박자({@link #genreStep})
 *       DRAFT_READY    → 승격({@link #promoteStep})
 *       PLACE_STAGED   → 전시장 초기화({@link #placeInitStep})
 *       </pre></li>
 * </ol>
 *
 * <p><b>발견과 실행을 가른다</b>: syncCatalog는 발견해서 이벤트만 남기고, 실제 외부 조회는 전부 릴레이가 소비한다
 * — 아웃박스 밖에서 도는 특수 실행 경로가 없다(모든 실패가 같은 백오프·at-least-once를 탄다).
 *
 * <p>영속성 로직은 일절 들지 않는다 — 소비 수명주기(선별→전이·낙관락 skip·예외 번역)는
 * {@link ExhibitionOutboxService#consume}(메커니즘)이 지고, 여기 스텝 메서드는 [① 판정(tx) → ② 외부 호출(tx 밖) →
 * ③ 반영(tx)] 3박자의 서비스 호출 합성 + 예외→{@link StepResult} 매핑만 한다. try/catch 없음 — 예외 번역은
 * consume, 축별 실패 처리는 그 서비스 소유.
 *
 * <p><b>보상(compensation)은 설계하지 않는다</b>: 실패 처리는 롤백이 아니라 <b>전진 복구</b>다 — 일시 실패는
 * 백오프 재시도, 영구 실패(4xx·시도 소진, D5: 총 3회)는 진행 상태를 FAILED로 가시화한다(조용한 영구 미승격 금지 —
 * 상세·장르·승격 전 스텝 공통). 크래시 복구는 어느 지점이든 동일하다: 마지막으로 커밋된 이벤트부터 릴레이가 다시 집는다.
 */
@Component
@RequiredArgsConstructor
public class ExhibitionIngestionOrchestrator {

	private final ExhibitionKoreaCultureService cultureService;
	private final ExhibitionAiGenreService genreService;
	private final ExhibitionPlaceService placeService;
	private final ExhibitionProgressService progressService;
	private final ExhibitionOutboxService outboxService;
	private final IngestionRunRecorder ingestionRunRecorder;

	// ──────────────────────────────────────────────────────────────────────────
	// 수집 진입점 — 목록 sync 루프
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * 외부 전시 API 수집 루프 — <b>트랜잭션 밖 조율</b>이다. 수집 요청 정책(무엇을 얼마나)은 culture 축 소유라
	 * {@link ExhibitionKoreaCultureService#fetchPages}가 내부에서 조립한다.
	 *
	 * <p><b>목록 외 외부 호출 0</b>: 이 루프는 목록만 받고, 행마다 스테이징(원장+진행+이벤트 2종 원자)만 한다 —
	 * 상세·AI·구글은 전부 이벤트 소비로 위임되어 릴레이가 처리한다. 수집 목표는 <b>신규 등록 포착</b>이다
	 * (등록 역순 순회 + 전량 기지 페이지에서 조기 종료).
	 *
	 * @param trigger 계기(BOOT/SCHEDULE/MANUAL) — ingestion_run.trigger_type에 남는다("왜 이 시각에 돌았나")
	 */
	public void syncCatalog(SyncTrigger trigger) {
		LocalDateTime syncedAt = LocalDateTime.now();
		IngestionRun run = IngestionRun.started(trigger, syncedAt);
		var items = cultureService.fetchPages(syncedAt);
		run.fetched(items.size());
		for (CatalogExhibitionData data : items) {
			// 기간 불량 판단·단건 실패(DEFERRED) 삼킴은 스테이징 소유 — 벤더 원장은 불량 행도 남긴다(ADR-13).
			if (progressService.stageFromList(data, syncedAt) == StageOutcome.STAGED) {
				run.recordStaged();
			}
		}
		ingestionRunRecorder.record(run);
	}

	// ──────────────────────────────────────────────────────────────────────────
	// 이벤트 소비 — 이벤트 타입 → 스텝 매핑(각 consumeX가 한 줄씩)
	// ──────────────────────────────────────────────────────────────────────────

	/** DRAFT_STAGED 소비 → 상세 3박자. 영구 실패는 진행 상태 FAILED로 가시화(콜백). */
	public void consumeDetailFetch() {
		outboxService.consume(IngestionEventType.DRAFT_STAGED, this::detailStep, this::visualizeProgressFailure);
	}

	/**
	 * DETAIL_FETCHED 소비 → 장르 3박자. 다중 배치 루프(실행당 처리 상한 = batch-size × max-batches)는 장르 축의
	 * 유량 정책이라 그 값만 넘기고 반복은 consume이 돈다. <b>영구 실패 콜백이 여기도 달린다(D5)</b> — 무기한 재시도
	 * 특례가 폐지되어 장르도 시도 소진(총 3회)으로 굳을 수 있고, 굳으면 진행 상태 FAILED로 보여야 한다.
	 */
	public void consumeGenreClassification() {
		outboxService.consume(IngestionEventType.DETAIL_FETCHED,
				genreService.consumeBatchSize(), genreService.maxBatchesPerRun(),
				this::genreStep, this::visualizeProgressFailure);
	}

	/** DRAFT_READY 소비 → 승격(어셈블). 영구 실패는 진행 상태 FAILED로 가시화(콜백). */
	public void consumePromotion() {
		outboxService.consume(IngestionEventType.DRAFT_READY, this::promoteStep, this::visualizeProgressFailure);
	}

	/**
	 * PLACE_STAGED 소비 → 전시장 초기화(설계 §2 A안 — 전시장 축). 진행 상태와 무관한 축이라 영구 실패 콜백이
	 * 없다 — 승격 게이트는 전시장 축을 기다리지 않고(비차단), 실패해도 영업시간만 빈다(D4 정책 수용).
	 */
	public void consumePlaceInitialization() {
		outboxService.consume(IngestionEventType.PLACE_STAGED, this::placeInitStep);
	}

	// ──────────────────────────────────────────────────────────────────────────
	// 스텝 메서드 — 서비스 호출 합성만. try/catch 없음(예외 번역은 outbox consume 소유).
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * 상세 스텝 — [① 판정 → ② 상세 조회+콜 감사(tx 밖) → ③ 반영 tx{상세 원장 + 마커 + DETAIL_FETCHED}].
	 * 해소됐거나 대상이 없으면 외부 호출 없이 성공 — 할 일 없음도 멱등 소비의 성공 마감이다(미존재 행이 나중에
	 * 생기면 재sync 안전망 {@code enqueueOrReactivate}가 이벤트를 부활시킨다).
	 */
	private StepResult detailStep(OutboxMessage message) {
		String externalId = message.getTargetKey();
		if (!progressService.needsDetail(externalId)) {                              // ① 판정(tx)
			return StepResult.success();
		}
		CultureDetailPayload detail = cultureService.fetchDetail(externalId);        // ② 외부 호출(tx 밖)
		progressService.applyDetail(externalId, detail, LocalDateTime.now());        // ③ 반영(tx)
		return StepResult.success();
	}

	/**
	 * 장르 스텝 — 이벤트당 <b>개별 AI 호출</b>(배치 분류 재도입 금지), 폴백 체인(Gemini→OpenAI)은 분류기 내부.
	 * [① 판정 → 입력 조립(원장) → ② AI 호출+콜 감사(tx 밖) → ③ 반영 tx{장르 원장 + 마커 + 게이트 검사}].
	 * 입력이 비면 대상이 아니다 — 할 일 없음도 멱등 소비의 성공 마감이다.
	 */
	private StepResult genreStep(OutboxMessage message) {
		String externalId = message.getTargetKey();
		if (!progressService.needsGenre(externalId)) {                               // ① 판정(tx)
			return StepResult.success();
		}
		Optional<GenreClassification> input = cultureService.genreInputOf(externalId); // 입력 조립(원장 읽기)
		if (input.isEmpty()) {
			return StepResult.success(); // 원장 없음 — 분류할 대상이 아니다(재sync가 치유).
		}
		var result = genreService.classify(externalId, input.get());                 // ② 외부 호출(tx 밖)
		progressService.applyGenre(externalId, result, LocalDateTime.now());         // ③ 반영(tx)
		return StepResult.success();
	}

	/**
	 * 승격 스텝 — 실제 완주는 {@link ExhibitionProgressService#completePromotion}의 한 트랜잭션([진행 재조회·게이트
	 * 재검사 → 원장 3종 어셈블 → 코어 등록 계약(멱등) → 진행 종료]). 외부 호출이 없는 유일한 스텝이다(등록은 같은 DB).
	 */
	private StepResult promoteStep(OutboxMessage message) {
		// 재전달·경합(종료·게이트 미충족)은 내부에서 no-op — 할 일 없음도 성공 마감이다(멱등 소비).
		progressService.completePromotion(message.getTargetKey(), LocalDateTime.now());
		return StepResult.success();
	}

	/**
	 * 전시장 초기화 스텝(설계 D4 — 영업시간 조회의 유일한 경로) — [시드 해소(진행→목록 원장) →
	 * resolve-or-create(tx·코어 계약) → 신규/기존 마크 → 신규만 ② 구글 조회+콜 감사(tx 밖) →
	 * ③ 반영 tx{구글 스냅샷 + place_hours}]. 기존 전시장이면 구글 호출 없이 끝난다(장소당 1콜 원칙).
	 */
	private StepResult placeInitStep(OutboxMessage message) {
		String placeKey = message.getTargetKey();
		Optional<String> externalId = progressService.findAnyExternalIdByPlaceKey(placeKey);
		if (externalId.isEmpty()) {
			return StepResult.success(); // 그 장소를 기다리는 진행 행이 없다 — 대상 소멸도 성공 마감.
		}
		Optional<CatalogExhibitionData> seed = cultureService.catalogDataOf(externalId.get());
		if (seed.isEmpty()) {
			return StepResult.success(); // 목록 원장 결손(비정상 창) — 재sync가 원장을 복원하면 다시 온다.
		}
		PlaceRegistrar.Resolved resolved = placeService.resolvePlace(seed.get());
		progressService.markPlaceOutcome(placeKey, resolved.created(), LocalDateTime.now());
		if (!resolved.created()) {
			return StepResult.success(); // 기존 전시장 재사용 — 구글 호출 없음(재검증도 없음, D4).
		}
		PlaceHoursTarget target = new PlaceHoursTarget(resolved.exhibitionPlaceId(), seed.get().place(), null);
		PlaceHoursResult result = placeService.read(target).orElse(null);            // ② 외부 호출(tx 밖)
		placeService.applyVenueHours(target, result, LocalDateTime.now());           // ③ 반영(tx)
		return StepResult.success();
	}

	/** 필수 스텝 영구 실패 콜백 — 진행이 조용히 영구 미승격으로 숨지 않게 FAILED로 가시화한다(상세·장르·승격 공용). */
	private void visualizeProgressFailure(OutboxMessage message) {
		progressService.markStepPermanentlyFailed(message.getTargetKey(), message.getLastError(), LocalDateTime.now());
	}
}
