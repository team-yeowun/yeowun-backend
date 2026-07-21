package modi.backend.ingestion.application.enricher;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.draft.DraftEnrichmentService;
import modi.backend.ingestion.application.draft.ExhibitionDraftFacade;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.application.outbox.OutboxFailures;
import modi.backend.ingestion.application.outbox.OutboxProcessing;
import modi.backend.ingestion.properties.OutboxProperties;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;

/**
 * 승격 처리기 — 전시 아웃박스({@link OutboxMessageType#EXHIBITION_READY})의 도래 메시지를 집어 draft를 코어로
 * 승격시킨다(ADR-12의 소비 측). 실제 완주는 {@link ExhibitionDraftFacade#completePromotion}의 한 트랜잭션 —
 * [draft 재조회 → 코어 등록 계약(멱등) → 영업시간 재검증 enqueue → draft 종료].
 *
 * <p>외부 호출이 없는 유일한 처리기다(등록은 같은 DB) — 실패는 대부분 일시 경합(낙관락)이거나 데이터 문제다.
 * 낙관락 충돌은 skip(다른 워커 선점), 그 외 실패는 백오프 재시도(RETRYABLE)로 남기고, 시도 소진으로 PERMANENT로
 * 굳으면 draft도 FAILED로 종료해 운영자에게 보인다(영구 미승격이 조용히 숨지 않게 — 상세 스텝과 동형).
 */
@Component
@RequiredArgsConstructor
public class DraftPromoter {

	private static final Logger log = LoggerFactory.getLogger(DraftPromoter.class);

	private final ExhibitionOutboxFacade exhibitionOutboxFacade;
	private final ExhibitionDraftFacade exhibitionDraftFacade;
	private final DraftEnrichmentService draftEnrichmentService;
	private final OutboxProperties properties;

	/**
	 * 도래한 EXHIBITION_READY 메시지를 배치로 처리한다. 스테디 상태에선 게이트를 갓 채운 draft만 도래해 있다.
	 *
	 * @return 이번 실행에서 상태를 전이시킨(성공·실패) 메시지 수(낙관락 skip 제외)
	 */
	public int promoteReady() {
		LocalDateTime now = LocalDateTime.now();
		List<OutboxMessage> messages = exhibitionOutboxFacade.findDue(OutboxMessageType.EXHIBITION_READY,
				properties.batchSize(), now);
		int processed = 0;
		for (OutboxMessage message : messages) {
			if (processOne(message, now)) {
				processed++;
			}
		}
		if (processed > 0) {
			log.info("승격 메시지 처리 {}건", processed);
		}
		return processed;
	}

	/** @return true면 전이함(성공/실패 기록), false면 낙관락 충돌로 skip(다른 워커가 처리). */
	private boolean processOne(OutboxMessage message, LocalDateTime now) {
		String externalId = message.getTargetKey();
		try {
			// 재전달·경합(종료 draft·게이트 미충족)은 내부에서 no-op — 할 일 없음도 성공 마감이다(멱등 소비).
			draftEnrichmentService.promoteStep(externalId, now);
		} catch (OptimisticLockingFailureException e) {
			return false; // 반영 중 충돌 — 다른 워커가 처리
		} catch (RuntimeException e) {
			boolean transitioned = OutboxProcessing.fail(exhibitionOutboxFacade, message,
					OutboxFailures.classify(e), OutboxFailures.describe(e), now);
			if (transitioned && message.getStatus() == OutboxMessageStatus.FAILED_PERMANENT) {
				// 승격이 영구 실패 — draft가 조용히 영구 미승격으로 숨지 않게 FAILED로 가시화한다.
				exhibitionDraftFacade.markStepPermanentlyFailed(externalId, OutboxFailures.describe(e), now);
			}
			return transitioned;
		}
		return OutboxProcessing.succeed(exhibitionOutboxFacade, message, now);
	}
}
