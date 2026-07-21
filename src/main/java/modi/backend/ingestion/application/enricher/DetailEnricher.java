package modi.backend.ingestion.application.enricher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionBackfill;
import modi.backend.application.exhibition.contract.DetailTargetState;
import modi.backend.ingestion.application.ExhibitionSyncFacade;
import modi.backend.ingestion.application.draft.DraftEnrichmentService;
import modi.backend.ingestion.application.draft.ExhibitionDraftFacade;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.application.outbox.OutboxFailures;
import modi.backend.ingestion.application.outbox.OutboxProcessing;
import modi.backend.ingestion.properties.OutboxProperties;
import modi.backend.ingestion.domain.data.DetailFetch;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * 상세(detail2) 처리기 — 전시 아웃박스({@link OutboxMessageType#FETCH_DETAIL})의 도래 메시지를 집어 상세를 조회한다.
 *
 * <p>대상 해소는 <b>draft 우선, 전시 폴백</b> 이원화다(ADR-10): 신규 유입은 draft의 상세분을 채우고 같은
 * 트랜잭션에서 다음 필수 스텝(CLASSIFY_GENRE)이 걸린다(스텝 체인). 레거시(이미 승격됐지만 상세 미완성) 전시는
 * 기존 경로({@code applyDetailForJob})로 satellite를 채운다.
 *
 * <p>외부 호출({@code fetchDetail})은 트랜잭션 밖에서 하고 반영·상태 전이만 트랜잭션에 위임한다({@link GenreEnricher}와
 * 동형). timeout·5xx·429는 백오프 재시도(RETRYABLE), 4xx·파싱실패는 즉시 영구 실패, 최대 시도 초과는 RETRYABLE도
 * PERMANENT로 승격한다({@link OutboxFailures}). <b>draft의 필수 스텝이 PERMANENT로 굳으면 draft도 FAILED로 종료</b>해
 * 운영자에게 보인다(영구 미승격이 조용히 숨지 않게).
 */
@Component
@RequiredArgsConstructor
public class DetailEnricher {

	private static final Logger log = LoggerFactory.getLogger(DetailEnricher.class);

	private final ExhibitionOutboxFacade exhibitionOutboxFacade;
	private final ExhibitionSyncFacade exhibitionSyncFacade;
	/** 레거시 전시 뒤채움 계약(코어 소유) — 대상 판정·무상세 확인. */
	private final ExhibitionBackfill exhibitionBackfill;
	private final ExhibitionDraftFacade exhibitionDraftFacade;
	/** draft 스텝 처리 단일 진입점 — 상세 스텝 3박자(판정→외부 호출→반영)를 수행한다. */
	private final DraftEnrichmentService draftEnrichmentService;
	private final ExhibitionCatalogClient catalogClient;
	private final OutboxProperties properties;

	/**
	 * 도래한 FETCH_DETAIL 메시지를 배치로 처리한다. 스테디 상태(재시도 없음)에선 도래 메시지가 없어 외부 호출 없이 끝난다.
	 *
	 * @return 이번 실행에서 상태를 전이시킨(성공·실패) 메시지 수(낙관락 skip 제외)
	 */
	public int enrichDetails() {
		LocalDateTime now = LocalDateTime.now();
		List<OutboxMessage> messages = exhibitionOutboxFacade.findDue(OutboxMessageType.FETCH_DETAIL,
				properties.batchSize(), now);
		int processed = 0;
		for (OutboxMessage message : messages) {
			if (processOne(message, now)) {
				processed++;
			}
		}
		if (processed > 0) {
			log.info("상세 메시지 처리 {}건", processed);
		}
		return processed;
	}

	/** @return true면 전이함(성공/실패 기록), false면 낙관락 충돌로 skip(다른 워커가 처리). */
	private boolean processOne(OutboxMessage message, LocalDateTime now) {
		String externalId = message.getTargetKey();
		if (exhibitionDraftFacade.needsDetail(externalId)) {
			return processDraft(message, externalId, now);
		}
		if (exhibitionDraftFacade.hasActiveDraft(externalId)) {
			// 상세 스텝은 이미 해소된 미종료 draft(장르 대기 중) — 재전달 메시지를 "대상 미존재"로 오판해
			// RETRYABLE을 반복하다 PERMANENT로 굳는 노이즈를 막고, 할 일 없음으로 마감한다.
			return OutboxProcessing.succeed(exhibitionOutboxFacade, message, now);
		}
		return processExhibition(message, externalId, now);
	}

	/** draft 경로 — 스텝 서비스가 [판정 → 상세 조회(tx 밖) → 반영(tx)] 3박자를 수행한다(장르 체인 포함). */
	private boolean processDraft(OutboxMessage message, String externalId, LocalDateTime now) {
		try {
			draftEnrichmentService.resolveDetailStep(externalId, now);
		} catch (OptimisticLockingFailureException e) {
			return false; // 반영 중 충돌 — 다른 워커가 처리
		} catch (RuntimeException e) {
			return failDraftStep(message, externalId, e, now);
		}
		return OutboxProcessing.succeed(exhibitionOutboxFacade, message, now);
	}

	/** draft 필수 스텝 실패 — 메시지를 실패 전이하고, PERMANENT로 굳었으면 draft도 FAILED로 종료한다. */
	private boolean failDraftStep(OutboxMessage message, String externalId, RuntimeException e, LocalDateTime now) {
		boolean transitioned = OutboxProcessing.fail(exhibitionOutboxFacade, message,
				OutboxFailures.classify(e), OutboxFailures.describe(e), now);
		if (transitioned && message.getStatus() == OutboxMessageStatus.FAILED_PERMANENT) {
			// 필수 스텝이 영구 실패 — draft가 조용히 영구 미승격으로 숨지 않게 FAILED로 가시화한다.
			exhibitionDraftFacade.markStepPermanentlyFailed(externalId, OutboxFailures.describe(e), now);
		}
		return transitioned;
	}

	/** 전시 폴백 경로(레거시 미완성 행) — 기존 satellite 채움 의미론 그대로. */
	private boolean processExhibition(OutboxMessage message, String externalId, LocalDateTime now) {
		DetailTargetState state = exhibitionBackfill.findDetailTargetState(externalId);
		if (state == DetailTargetState.ALREADY_SYNCED) {
			// 다른 경로가 이미 상세를 채웠다(또는 draft가 승격 시 채움) — 할 일 없음.
			return OutboxProcessing.succeed(exhibitionOutboxFacade, message, now);
		}
		if (state == DetailTargetState.MISSING) {
			// draft도 전시도 없다 — 다음 카탈로그 sync가 스테이징한다. 그때까지 재시도 대상으로 둔다.
			return OutboxProcessing.fail(exhibitionOutboxFacade, message, OutboxFailureType.RETRYABLE,
					"대상 미존재 — 다음 카탈로그 동기화가 스테이징 예정", now);
		}
		Optional<DetailFetch> detail;
		try {
			detail = catalogClient.fetchDetailSnapshot(externalId); // 트랜잭션 밖 외부 호출
		} catch (RuntimeException e) {
			return OutboxProcessing.fail(exhibitionOutboxFacade, message,
					OutboxFailures.classify(e), OutboxFailures.describe(e), now);
		}
		try {
			detail.ifPresentOrElse(f -> exhibitionSyncFacade.applyLegacyDetail(externalId, f),
					() -> exhibitionBackfill.markDetailChecked(externalId, now));
		} catch (OptimisticLockingFailureException e) {
			return false; // 반영 중 충돌 — 다른 워커가 처리
		} catch (RuntimeException e) {
			return OutboxProcessing.fail(exhibitionOutboxFacade, message,
					OutboxFailures.classify(e), OutboxFailures.describe(e), now);
		}
		return OutboxProcessing.succeed(exhibitionOutboxFacade, message, now);
	}
}
