package modi.backend.ingestion.application;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.audit.IngestionRunRecorder;
import modi.backend.ingestion.application.culture.ExhibitionKoreaCultureService;
import modi.backend.ingestion.application.draft.ExhibitionDraftService;
import modi.backend.ingestion.application.genre.ExhibitionAiGenreService;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.application.outbox.StepResult;
import modi.backend.ingestion.application.place.ExhibitionPlaceService;
import modi.backend.ingestion.domain.SyncTrigger;
import modi.backend.ingestion.domain.audit.IngestionRun;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;

/**
 * 전시 수집 파이프라인의 <b>오케스트레이션 전담 파사드</b>(설계 §4) — application 루트에 단 하나다.
 * "흐름이 궁금하면 이 파일 하나"가 이 구조의 가독성 계약이다. 소유하는 것은 둘뿐이다:
 * <ol>
 *   <li><b>수집 진입점</b>({@link #syncCatalog}): 이 슬라이스가 <b>한 회차에 할 일을 찾아 큐에 싣는</b> 유일한
 *       메서드다 — 목록 수집·스테이징(DRAFT_STAGED 발행)과 영업시간 확인 대상 스윕(PLACE_HOURS_STALE 발행)이
 *       모두 여기 안이다. 스케줄러는 이걸 부르는 트리거일 뿐, 보강 메서드를 따로 알지 못한다.</li>
 *   <li><b>이벤트 소비</b>: 릴레이가 도래 이벤트를 집어오면 <b>이벤트 타입 → 다음 스텝</b> 매핑을 실행한다.
 *       스텝 순서 지식은 이 매핑 한 곳에만 있다(설계 §1 — 서비스는 자기 사실만 발행한다):
 *       <pre>
 *       DRAFT_STAGED      → 상세 3박자({@link #detailStep})
 *       DETAIL_FETCHED    → 장르 3박자({@link #genreStep})
 *       DRAFT_READY       → 승격({@link #promoteStep})
 *       PLACE_HOURS_STALE → 영업시간 조회({@link #hoursStep})
 *       </pre></li>
 * </ol>
 *
 * <p><b>발견과 실행을 가른다</b>: syncCatalog는 발견해서 이벤트만 남기고, 실제 외부 조회는 전부 릴레이가 드레인한다
 * — 그래서 아웃박스 밖에서 도는 특수 실행 경로가 없다(모든 실패가 같은 백오프·at-least-once를 탄다).
 *
 * <p>영속성 로직(스냅샷·아웃박스·콜 로그)은 일절 들지 않는다 — 소비 수명주기(선별→전이·낙관락 skip)는
 * {@link ExhibitionOutboxService#drain}(메커니즘)이 지고, 여기 스텝 메서드는 [① 판정(tx) → ② 외부 호출(tx 밖) →
 * ③ 반영(tx)] 3박자의 서비스 호출 합성 + 예외→{@link StepResult} 매핑만 한다 — 흐름제어의 최소 단위.
 * 외부 호출은 어떤 트랜잭션에도 속하지 않는다(설계 §0 — 커넥션 홀딩 안티패턴 방지).
 *
 * <p><b>보상(compensation)은 설계하지 않는다</b>(설계 §4): 이 파이프라인은 사실의 누적이라 되돌릴 것이 없다.
 * 실패 처리는 롤백이 아니라 <b>전진 복구</b>다 — 일시 실패(timeout·5xx·429)는 백오프 재시도(RETRYABLE), 영구
 * 실패(4xx·파싱 불가)는 재시도 소진 시 draft를 FAILED로 가시화한다(조용한 영구 미승격 금지). 모든 반영은 멱등이라
 * at-least-once 재전달에 안전하다. 크래시 복구는 어느 지점이든 동일하다: 마지막으로 커밋된 이벤트부터 릴레이가 다시 집는다.
 */
@Component
@RequiredArgsConstructor
public class ExhibitionIngestionOrchestrator {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionIngestionOrchestrator.class);

	private final ExhibitionKoreaCultureService cultureService;
	private final ExhibitionAiGenreService genreService;
	private final ExhibitionPlaceService placeService;
	private final ExhibitionDraftService draftService;
	private final ExhibitionOutboxService outboxService;
	/** 수집 런 감사(best-effort) — 기록 실패가 동기화를 깨지 않는다. 동기화 요약 로그도 recorder 소유다. */
	private final IngestionRunRecorder ingestionRunRecorder;

	// ──────────────────────────────────────────────────────────────────────────
	// 수집 진입점 — 목록 sync 루프
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * 외부 전시 API 수집 루프 — <b>트랜잭션 밖 조율</b>이다. 수집 요청 정책(무엇을 얼마나)은 culture 축 소유라
	 * {@link ExhibitionKoreaCultureService#fetchPages}가 내부에서 조립한다.
	 *
	 * <p><b>목록 외 외부 호출 0</b>(ADR-10): 이 루프는 목록만 받고, 행마다 [벤더 원본 적재 + draft 스테이징
	 * (DRAFT_STAGED 원자 발행)]만 한다 — 상세 조회·AI 분류는 전부 이벤트 소비로 위임되어 릴레이가 처리한다
	 * (예전엔 신규·미완성 행마다 상세를 인라인 호출해 초기 적재 시 수백 콜이 이 루프에 물렸다).
	 *
	 * <p><b>수집 목표는 신규 등록 포착</b>이다(사용자 결정). 원천을 등록 역순으로 읽다가 <b>페이지 전량이 이미 아는
	 * 항목</b>이면 거기서 순회를 멈춘다 — 그 뒤로는 신규가 없기 때문이다. 전량 정합(변경·소멸 감지)은 목표가 아니다.
	 *
	 * <p>결과 집계는 반환하지 않는다 — 수치는 {@link IngestionRun}(감사 테이블)의 상태이고, 요약 로그는
	 * {@link IngestionRunRecorder}가 남긴다. 호출부가 세어 나를 이유가 없다.
	 *
	 * @param trigger 계기(BOOT/SCHEDULE/MANUAL) — sync_run.trigger_type에 남긴다("왜 이 시각에 돌았나")
	 */
	public void syncCatalog(SyncTrigger trigger) {
		// 배치 전체가 같은 last_seen_at을 공유해야 "이번 동기화에 안 보인 행"(last_seen_at < 이 시각)이 한 번에 가려진다.
		// 아이템마다 now()를 찍으면 그 경계가 흐려진다.
		LocalDateTime syncedAt = LocalDateTime.now();
		IngestionRun run = IngestionRun.started(trigger, syncedAt);
		ExhibitionKoreaCultureService.Fetched fetched = cultureService.fetchPages(syncedAt);
		run.fetched(fetched.totalCount(), fetched.items().size());
		for (CatalogExhibitionData data : fetched.items()) {
			cultureService.archiveListSnapshot(data, syncedAt);
			if (!data.hasValidPeriod()) {
				run.recordPeriodSkipped();
				continue;
			}
			// 단건 실패(DEFERRED)는 스테이징이 자기 안에서 삼키고 결과로 알린다 — 여기선 집계만 한다.
			run.record(draftService.stageFromList(data, syncedAt));
		}
		// 영업시간 확인 대상 발견 — 목록 수집과 같은 회차의 "할 일 찾기"다. 실패를 삼키는 것도 그 축의 몫이다.
		placeService.sweepDueHours(syncedAt);
		ingestionRunRecorder.record(run);
	}

	// ──────────────────────────────────────────────────────────────────────────
	// 이벤트 소비 — 이벤트 타입 → 스텝 매핑(각 drainX가 한 줄씩)
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * DRAFT_STAGED 소비 → 상세 3박자({@link #detailStep}). 필수 스텝이 PERMANENT로 굳으면 draft도 FAILED로
	 * 가시화한다({@link #visualizeDraftFailure} 콜백 — 영구 미승격이 조용히 숨지 않게).
	 */
	public void drainDetailFetch() {
		outboxService.drain(IngestionEventType.DRAFT_STAGED, this::detailStep, this::visualizeDraftFailure);
	}

	/**
	 * DETAIL_FETCHED 소비 → 장르 3박자({@link #genreStep}). 다중 배치 루프(실행당 처리 상한 = batch-size ×
	 * max-batches)는 장르 축의 유량 정책이라 그 값만 넘기고 반복은 drain이 돈다 — 대량 재백필의 유량 제어.
	 */
	public void drainGenreClassification() {
		outboxService.drain(IngestionEventType.DETAIL_FETCHED,
				genreService.drainBatchSize(), genreService.maxBatchesPerRun(), this::genreStep);
	}

	/**
	 * DRAFT_READY 소비 → 승격({@link #promoteStep}). 시도 소진으로 PERMANENT로 굳으면 draft도 FAILED로
	 * 가시화한다(상세 스텝과 동형).
	 */
	public void drainPromotion() {
		outboxService.drain(IngestionEventType.DRAFT_READY, this::promoteStep, this::visualizeDraftFailure);
	}

	/**
	 * PLACE_HOURS_STALE 소비 → 영업시간 조회({@link #hoursStep}, 설계 §4-1). 발행자는 둘이다: 승격(기존 장소에
	 * 새 전시 유입)과 syncCatalog의 스윕(미조회·만료 발견) — 소비는 이 한 경로라 조회·재시도가 한 벌이다.
	 * mock provider가 기본이라 로컬·CI·develop에선 유료 호출 없이 동일 경로가 돈다.
	 */
	public void drainPlaceHours() {
		outboxService.drain(IngestionEventType.PLACE_HOURS_STALE, this::hoursStep);
	}

	// ──────────────────────────────────────────────────────────────────────────
	// 스텝 메서드 — <b>서비스 호출 합성만</b>. try/catch가 없다: 예외의 의미(선점·재시도·영구 실패) 번역은
	// outboxService.drain이, 각 축의 실패 처리(정준층 기록·삼킴)는 그 서비스가 자기 안에서 진다.
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * 상세 스텝 — draft 단일 경로다(레거시 전시 폴백은 소비 대상 소멸로 제거). 상세 미해소 draft는
	 * [② 상세 조회+콜 로그+원문 보관(tx 밖) → ③ draft 반영+DETAIL_FETCHED 발행(tx)]. 해소됐거나 대상이 없으면
	 * 외부 호출 없이 성공 — 할 일 없음도 멱등 소비의 성공 마감이다(미존재 draft가 나중에 생기면 재sync 안전망
	 * {@code enqueueOrReactivate}가 이벤트를 부활시킨다). timeout·5xx·429는 RETRYABLE, 4xx·파싱실패는 즉시
	 * PERMANENT, 최대 시도 초과는 RETRYABLE도 PERMANENT로 승격한다(실패 해석은 아웃박스 drain 소유).
	 */
	private StepResult detailStep(OutboxMessage message) {
		String externalId = message.getTargetKey();
		if (!draftService.needsDetail(externalId)) {                                // ① 판정(tx)
			return StepResult.success();
		}
		CultureDetailPayload detail = cultureService.fetchDetail(externalId);       // ② 외부 호출(tx 밖)
		draftService.applyDetail(externalId, detail, LocalDateTime.now());          // ③ 반영(tx)
		return StepResult.success();
	}

	/**
	 * 장르 스텝 — 이벤트당 <b>개별 AI 호출</b>(사용자 확정·ADR-12), draft 단일 경로. <b>계약 반전(ADR-11)</b>:
	 * 분류기는 실패 시 폴백값 대신 예외를 던진다 — 실패는 <b>무조건 RETRYABLE 고정</b>이다(장르는 시도 소진 없는
	 * 무기한 정책). 일반 분류 규칙을 태우면 cause 체인의 IllegalArgumentException 등이 PERMANENT로
	 * 분류돼 draft가 조용히 영구 미승격으로 남는 비대칭이 생긴다(상세와 달리 장르엔 draft FAILED 연동이 없다).
	 */
	private StepResult genreStep(OutboxMessage message) {
		String externalId = message.getTargetKey();
		// [① 판정(tx) → ② 개별 AI 호출+콜 로그(tx 밖) → ③ 반영+게이트 검사(tx)].
		// 입력이 비면 대상이 아니다(이미 분류됐거나 종료 draft) — 할 일 없음도 멱등 소비의 성공 마감이다.
		draftService.resolveGenreInput(externalId).ifPresent(input ->
				draftService.applyGenre(externalId, genreService.classify(externalId, input), LocalDateTime.now()));
		return StepResult.success();
	}

	/**
	 * 승격 스텝 — 실제 완주는 {@link ExhibitionDraftService#completePromotion}의 한 트랜잭션([draft 재조회 →
	 * 코어 등록 계약(멱등) → 영업시간 재검증 enqueue → draft 종료]). 외부 호출이 없는 유일한 스텝이다(등록은 같은
	 * DB) — 실패는 대부분 일시 경합(낙관락)이거나 데이터 문제라 RETRYABLE로 남긴다.
	 */
	private StepResult promoteStep(OutboxMessage message) {
		// 재전달·경합(종료 draft·게이트 미충족)은 내부에서 no-op — 할 일 없음도 성공 마감이다(멱등 소비).
		draftService.completePromotion(message.getTargetKey(), LocalDateTime.now());
		return StepResult.success();
	}

	/**
	 * 영업시간 스텝 — NO_DATA(미발견)도 스텝 해소다(시각을 남겨 재조회 백오프의 기준). 그 장소를 쓰는 전시가
	 * 더는 없으면 재검증할 대상이 없으니 성공으로 마감한다. 조회 실패는 정준층에도 남긴다(시도했고 실패했다).
	 */
	private StepResult hoursStep(OutboxMessage job) {
		// 대상 해소·조회·반영, 그리고 "실패도 정준층에 남긴다"는 전부 place 축 안이다(한 축에서 끝나는 스텝).
		placeService.refreshHours(job.getTargetKey());
		return StepResult.success();
	}

	/** 필수 스텝 영구 실패 콜백 — draft가 조용히 영구 미승격으로 숨지 않게 FAILED로 가시화한다(상세·승격 공용). */
	private void visualizeDraftFailure(OutboxMessage message) {
		draftService.markStepPermanentlyFailed(message.getTargetKey(), message.getLastError(), LocalDateTime.now());
	}

}
