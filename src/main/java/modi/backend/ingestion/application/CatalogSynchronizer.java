package modi.backend.ingestion.application;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.application.draft.ExhibitionDraftFacade;
import modi.backend.application.exhibition.contract.DetailTargetState;
import modi.backend.application.exhibition.contract.ExhibitionBackfill;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.domain.SyncTrigger;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.ingestion.domain.entity.IngestionRun;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.ingestion.properties.CatalogFetchProperties;

/**
 * 외부 전시 API 수집 루프 — <b>트랜잭션 밖 조율자</b>다(enricher와 동형).
 *
 * <p><b>목록 외 외부 호출 0</b>(ADR-10): 이 루프는 목록만 받고, 행마다 [벤더 원본 적재 + draft 스테이징 +
 * 필수 스텝 enqueue]만 한다 — 상세 조회·AI 분류는 전부 아웃박스 메시지로 위임되어 릴레이가 처리한다
 * (예전엔 신규·미완성 행마다 상세를 인라인 호출해 초기 적재 시 수백 콜이 이 루프에 물렸다).
 *
 * <p>신규는 {@link ExhibitionDraftFacade}가 스테이징하고(전시는 승격 게이트를 채워야만 생긴다 — ADR-02 완성),
 * 레거시 미완성 전시(draft 도입 전에 승격된 행)는 FETCH_DETAIL 메시지로 뒤채움을 위임한다.
 */
@Component
@RequiredArgsConstructor
public class CatalogSynchronizer {

	private static final Logger log = LoggerFactory.getLogger(CatalogSynchronizer.class);

	private final ExhibitionSyncFacade exhibitionSyncFacade;
	/** 레거시 전시 뒤채움 계약(코어 소유) — 목록 1건의 대상 상태 판정. */
	private final ExhibitionBackfill exhibitionBackfill;
	private final ExhibitionDraftFacade exhibitionDraftFacade;
	private final ExhibitionOutboxFacade exhibitionOutboxFacade;
	private final ExhibitionCatalogClient catalogClient;
	/** 수집 요청 정책(무엇을 얼마나) — 어댑터가 아니라 이 유스케이스가 정해 포트 인자로 내려보낸다. */
	private final CatalogFetchProperties fetchProperties;

	/**
	 * 정기(SCHEDULE) 동기화.
	 *
	 * @return 이번 동기화로 새로 스테이징된 draft 수(레거시 뒤채움·갱신 건은 제외)
	 */
	public int syncCatalog() {
		return syncCatalog(SyncTrigger.SCHEDULE);
	}

	/** 계기(BOOT/SCHEDULE/MANUAL)를 명시한 동기화 — sync_run.trigger_type에 남긴다("왜 이 시각에 돌았나"). */
	public int syncCatalog(SyncTrigger trigger) {
		// 배치 전체가 같은 last_seen_at을 공유해야 "이번 동기화에 안 보인 행"(last_seen_at < 이 시각)이 한 번에 가려진다.
		// 아이템마다 now()를 찍으면 그 경계가 흐려진다.
		LocalDateTime syncedAt = LocalDateTime.now();
		IngestionRun run = IngestionRun.started(trigger, syncedAt);
		CatalogListData fetched = catalogClient.fetchAll(fetchProperties.toCriteria());
		List<CatalogExhibitionData> collected = fetched.items();
		run.fetched(fetched.totalCount(), fetched.truncated(), collected.size());
		if (fetched.truncated()) {
			log.warn("전시 동기화 절단 — 원천 총 {}건 중 상한(max-pages × num-of-rows)에 걸려 일부만 수집됨",
					fetched.totalCount());
		}
		int staged = 0;
		int touched = 0;
		int skipped = 0;
		int deferred = 0;
		for (CatalogExhibitionData data : collected) {
			exhibitionSyncFacade.archiveListSnapshot(data, syncedAt);
			if (!hasValidPeriod(data)) {
				skipped++;
				continue;
			}
			try {
				switch (syncOne(data, syncedAt)) {
					case STAGED -> staged++;
					case BACKFILLED, REFRESHED -> touched++;
					case SKIPPED -> { /* 이미 완성/종료 — 변화 없음 */ }
				}
			} catch (RuntimeException e) {
				deferred++;
				log.warn("전시 동기화 단건 실패(externalId={}, 다음 주기 재시도): {}", data.externalId(), e.getMessage());
			}
		}
		if (skipped > 0 || touched > 0 || deferred > 0 || staged > 0) {
			log.info("전시 동기화: 수집 {} / 신규스테이징 {} / 갱신·뒤채움 {} / 기간스킵 {} / 실패연기 {}",
					collected.size(), staged, touched, skipped, deferred);
		}
		exhibitionSyncFacade.archiveIngestionRun(run, staged, touched, skipped, deferred);
		return staged;
	}

	private enum SyncOutcome {
		STAGED, BACKFILLED, REFRESHED, SKIPPED
	}

	/**
	 * 목록 1건 처리 — 외부 호출 없이 상태 판정과 영속 위임만 한다.
	 * 완성 전시=스킵, 레거시 미완성=FETCH_DETAIL 위임, 그 외=draft 스테이징(신규)·갱신(재sync).
	 */
	private SyncOutcome syncOne(CatalogExhibitionData data, LocalDateTime now) {
		DetailTargetState state = exhibitionBackfill.findDetailTargetState(data.externalId());
		if (state == DetailTargetState.ALREADY_SYNCED) {
			return SyncOutcome.SKIPPED;
		}
		if (state == DetailTargetState.NEEDS_DETAIL) {
			// draft 도입 전에 승격된 레거시 미완성 행 — 인라인 조회 대신 메시지로 뒤채움을 위임한다(멱등 enqueue).
			exhibitionOutboxFacade.enqueue(OutboxMessageType.FETCH_DETAIL, data.externalId(), now);
			return SyncOutcome.BACKFILLED;
		}
		return switch (exhibitionDraftFacade.stageFromList(data, now)) {
			case STAGED -> SyncOutcome.STAGED;
			case REFRESHED -> SyncOutcome.REFRESHED;
			case SKIPPED -> SyncOutcome.SKIPPED;
		};
	}

	private static boolean hasValidPeriod(CatalogExhibitionData data) {
		return data.startDate() == null || data.endDate() == null || !data.startDate().isAfter(data.endDate());
	}
}
