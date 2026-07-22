package modi.backend.ingestion.application.enricher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionBackfill;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.draft.DraftEnrichmentService;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.application.outbox.OutboxFailures;
import modi.backend.ingestion.application.outbox.OutboxProcessing;
import modi.backend.ingestion.properties.CatalogEnrichProperties;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;

/**
 * 장르 분류 처리기 — 전시 아웃박스({@link OutboxMessageType#CLASSIFY_GENRE}) 기반, <b>메시지당 개별 AI 호출</b>
 * (사용자 확정, ADR-12 — 배치 1콜은 "0에서 전량 초기화" 시절의 429 대응이었고, 스테디 상태 신규 유입은 몇 건이라
 * 단건으로 단순화했다. 대량 재백필의 유량은 실행당 처리 상한(batch-size × max-batches)과 폴링 주기·429 백오프가 제어한다).
 *
 * <p>대상 해소는 <b>draft 우선, 전시 폴백</b> 이원화(ADR-10): 신규 유입은 {@link DraftEnrichmentService}의
 * 장르 스텝(3박자)으로, 레거시(이미 승격됐지만 미분류) 전시는 코어 계약({@link ExhibitionBackfill})으로 반영한다.
 * 미분류 레거시의 발견은 스윕(멱등 enqueue)이, 신규 draft의 분류 메시지는 상세 해소가 건다(스텝 체인).
 *
 * <p><b>계약 반전(ADR-11)</b>: 분류기는 실패 시 폴백값 대신 {@code GenreClassificationException}을 던진다 —
 * 실패 메시지는 RETRYABLE로 남고(장르는 시도 소진 없는 무기한 정책), 회복 후 릴레이가 다시 집는다.
 */
@Component
@RequiredArgsConstructor
public class GenreEnricher {

	private static final Logger log = LoggerFactory.getLogger(GenreEnricher.class);

	/** 레거시 전시 뒤채움 계약(코어 소유) — 미분류 스윕·입력 해소·정준 반영. */
	private final ExhibitionBackfill exhibitionBackfill;
	private final DraftEnrichmentService draftEnrichmentService;
	private final ExhibitionOutboxFacade exhibitionOutboxFacade;
	private final CatalogEnrichProperties properties;
	/** 장르 분류 전략(AI 체인/mock) — 레거시 경로의 단건 분류에 쓴다(draft 경로는 스텝 서비스가 내장). */
	private final GenreClassifier genreClassifier;

	/**
	 * 미분류 레거시를 스윕하고 도래한 장르 메시지를 드레인한다. 실행당 처리 상한 = batch-size × max-batches
	 * (메시지당 AI 1콜 — 이 상한이 대량 재백필의 유량 제어다).
	 *
	 * @return 이번 실행에서 상태를 전이시킨 CLASSIFY_GENRE 메시지 수(분류·재시도·마감)
	 */
	public int enrichGenres() {
		sweepUnclassified(LocalDateTime.now());
		int total = 0;
		for (int i = 0; i < properties.genreMaxBatchesPerRun(); i++) {
			int processed = drainBatch(properties.genreBatchSize());
			total += processed;
			if (processed == 0) {
				break; // 도래 메시지 소진 → 조기 종료
			}
		}
		if (total > 0) {
			log.info("전시 장르 메시지 처리 {}건", total);
		}
		return total;
	}

	/** 미분류 레거시 CATALOG를 멱등 enqueue한다(이번 실행이 드레인할 수 있는 만큼만 — 나머진 다음 실행이 스윕). */
	private void sweepUnclassified(LocalDateTime now) {
		int sweepLimit = properties.genreBatchSize() * properties.genreMaxBatchesPerRun();
		List<String> externalIds = exhibitionBackfill.findUnclassifiedCatalogExternalIds(sweepLimit);
		if (!externalIds.isEmpty()) {
			exhibitionOutboxFacade.enqueueAll(OutboxMessageType.CLASSIFY_GENRE, externalIds, now);
		}
	}

	/** 한 배치 드레인 — 도래 메시지를 한 건씩 처리한다. @return 상태를 전이시킨 메시지 수(0이면 도래 없음) */
	private int drainBatch(int batchSize) {
		LocalDateTime now = LocalDateTime.now();
		List<OutboxMessage> messages = exhibitionOutboxFacade.findDue(OutboxMessageType.CLASSIFY_GENRE, batchSize, now);
		int transitioned = 0;
		for (OutboxMessage message : messages) {
			if (processOne(message, now)) {
				transitioned++;
			}
		}
		return transitioned;
	}

	/** @return true면 전이함(성공/실패 기록), false면 낙관락 충돌로 skip(다른 워커가 처리). */
	private boolean processOne(OutboxMessage message, LocalDateTime now) {
		String externalId = message.getTargetKey();
		try {
			// draft 우선 — 스텝 서비스가 [판정 → 개별 AI 호출(tx 밖) → 반영+게이트 검사(tx)] 3박자를 수행한다.
			if (draftEnrichmentService.classifyGenreStep(externalId, now)) {
				return OutboxProcessing.succeed(exhibitionOutboxFacade, message, now);
			}
			// 전시 폴백(레거시 미분류) — 코어 계약으로 [입력 해소 → 개별 AI 호출 → 정준 반영].
			GenreClassification input = exhibitionBackfill.resolveGenreInputs(List.of(externalId)).get(externalId);
			if (input == null) {
				// draft도 전시도 분류 대상이 아니다(이미 분류됐거나 사라짐) — 할 일 없으니 성공으로 마감.
				return OutboxProcessing.succeed(exhibitionOutboxFacade, message, now);
			}
			GenreResult result = genreClassifier.classify(genreRequest(input)); // tx 밖 AI 호출
			exhibitionBackfill.applyGenreResults(Map.of(externalId, result), now);
			return OutboxProcessing.succeed(exhibitionOutboxFacade, message, now);
		} catch (OptimisticLockingFailureException e) {
			return false; // 반영 중 충돌 — 다른 워커가 처리(메시지는 그 워커가 마감한다).
		} catch (RuntimeException e) {
			// 장르는 PERMANENT로 굳히지 않는다(ADR-11 무기한 정책의 취지) — 분류·반영 실패 모두 RETRYABLE 고정.
			// OutboxFailures.classify를 태우면 cause 체인의 IllegalArgumentException 등이 PERMANENT로 분류돼
			// draft가 조용히 영구 미승격으로 남는 비대칭이 생긴다(상세와 달리 장르엔 draft FAILED 연동이 없다).
			boolean transitioned = OutboxProcessing.fail(exhibitionOutboxFacade, message, OutboxFailureType.RETRYABLE,
					OutboxFailures.describe(e), now);
			if (transitioned) {
				log.warn("장르 분류 실패(externalId={}) — 회복 후 재시도: {}", externalId, e.getMessage());
			}
			return transitioned;
		}
	}

	/** 이 유스케이스가 분류기에게 무엇을 시킬지 — 표준 지시 + 마스터 전체 허용. 요청 조립은 서비스의 결정이다. */
	private GenreClassificationRequest genreRequest(GenreClassification subject) {
		return new GenreClassificationRequest(
				GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText());
	}

}
