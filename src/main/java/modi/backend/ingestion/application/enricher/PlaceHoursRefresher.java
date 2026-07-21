package modi.backend.ingestion.application.enricher;

import modi.backend.application.exhibition.contract.PlaceHoursBackfill;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.ingestion.application.ExhibitionSyncFacade;

import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.application.outbox.OutboxProcessing;
import modi.backend.ingestion.application.outbox.OutboxFailures;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.properties.OutboxProperties;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.domain.exhibition.hours.OpeningHoursFormatter;
import modi.backend.ingestion.domain.data.PlaceHoursFetch;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;

/**
 * 이벤트 구동 영업시간 재검증 처리기(설계 §4-1) — 전시 아웃박스의 영업시간 메시지
 * ({@link OutboxMessageType#REFRESH_PLACE_HOURS}·{@link OutboxMessageType#FETCH_PLACE_HOURS})을 드레인한다.
 *
 * <p>재검증 enqueue는 카탈로그 sync가 "새 전시가 기존 장소에 유입"될 때 걸고(가드: 기존 장소만·최소 간격·UK 중복),
 * 여기서 도래한 작업을 집어 그 장소의 영업시간을 다시 조회한다 — 같은 큐·같은 at-least-once·같은 비용상한이다.
 * 외부 호출은 트랜잭션 밖, 반영·상태 전이만 트랜잭션({@link PlaceHoursEnricher}와 동형). mock provider가 기본이라
 * 로컬·CI·develop에선 유료 호출 없이 동일 경로가 돈다.
 */
@Component
@RequiredArgsConstructor
public class PlaceHoursRefresher {

	private static final Logger log = LoggerFactory.getLogger(PlaceHoursRefresher.class);

	/** 최초 조회(FETCH)는 현재 {@link PlaceHoursEnricher}가 직접 하지만, 큐로 들어온 FETCH도 같은 경로로 처리한다. */
	private static final List<OutboxMessageType> HOURS_JOB_TYPES = List.of(
			OutboxMessageType.REFRESH_PLACE_HOURS, OutboxMessageType.FETCH_PLACE_HOURS);

	private final ExhibitionOutboxFacade exhibitionOutboxFacade;
	private final ExhibitionSyncFacade exhibitionSyncFacade;
	/** 영업시간 정준층 계약(코어 소유) — 대상 해소·실패 기록. */
	private final PlaceHoursBackfill placeHoursBackfill;
	/** 조회 1건 + 그 호출의 감사. */
	private final PlaceHoursReader placeHoursReader;
	private final OpeningHoursFormatter openingHoursFormatter;
	private final OutboxProperties properties;

	/**
	 * 도래한 영업시간 작업(재검증·최초조회)을 배치로 처리한다. 도래 작업이 없으면 외부 호출 없이 끝난다.
	 *
	 * @return 이번 실행에서 상태를 전이시킨 작업 수(낙관락 skip 제외)
	 */
	public int refreshDueHours() {
		LocalDateTime now = LocalDateTime.now();
		int processed = 0;
		for (OutboxMessageType messageType : HOURS_JOB_TYPES) {
			List<OutboxMessage> jobs = exhibitionOutboxFacade.findDue(messageType, properties.batchSize(), now);
			for (OutboxMessage job : jobs) {
				if (processOne(job, now)) {
					processed++;
				}
			}
		}
		if (processed > 0) {
			log.info("영업시간 재검증 처리 {}건", processed);
		}
		return processed;
	}

	/** @return true면 전이함, false면 낙관락 충돌로 skip(다른 워커가 처리). */
	private boolean processOne(OutboxMessage job, LocalDateTime now) {
		String placeKey = job.getTargetKey();
		Optional<PlaceHoursTarget> resolved = placeHoursBackfill.resolvePlaceHoursTarget(placeKey);
		if (resolved.isEmpty()) {
			// 그 장소를 쓰는 전시가 더는 없다 — 재검증할 대상이 없으니 성공으로 마감한다.
			return OutboxProcessing.succeed(exhibitionOutboxFacade, job, now);
		}
		PlaceHoursTarget target = resolved.get();
		try {
			Optional<PlaceHoursFetch> fetched = placeHoursReader.read(target);
			String formatted = fetched.map(f -> openingHoursFormatter.format(f.data().weeklyHours())).orElse(null);
			exhibitionSyncFacade.applyVenueHours(target, fetched.map(PlaceHoursFetch::data).orElse(null),
						fetched.map(PlaceHoursFetch::vendor).orElse(null), formatted, placeHoursReader.vendor(), now);
		} catch (org.springframework.dao.OptimisticLockingFailureException e) {
			return false; // 반영 중 충돌 — 다른 워커가 처리
		} catch (RuntimeException e) {
			placeHoursBackfill.markHoursFailure(target.exhibitionPlaceId(), placeHoursReader.vendor());
			return OutboxProcessing.fail(exhibitionOutboxFacade, job,
					OutboxFailures.classify(e), OutboxFailures.describe(e), now);
		}
		return OutboxProcessing.succeed(exhibitionOutboxFacade, job, now);
	}
}
