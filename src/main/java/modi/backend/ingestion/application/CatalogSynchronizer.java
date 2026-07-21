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
 * <p>신규는 {@link ExhibitionDraftFacade}가 스테이징한다(전시는 승격 게이트를 채워야만 생긴다 — ADR-02 완성).
 *
 * <p><b>수집 목표는 신규 등록 포착</b>이다(사용자 결정). 원천을 등록 역순으로 읽다가 <b>페이지 전량이 이미 아는
 * 항목</b>이면 거기서 순회를 멈춘다 — 그 뒤로는 신규가 없기 때문이다. 전량 정합(변경·소멸 감지)은 목표가 아니며,
 * 레거시 뒤채움도 이 루프에서 하지 않는다(필요해지면 일회성 배치로 분리).
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
		CatalogListData fetched = catalogClient.fetchAll(fetchProperties.toCriteria(),
				exhibitionSyncFacade::allSnapshotted);
		List<CatalogExhibitionData> collected = fetched.items();
		run.fetched(fetched.totalCount(), collected.size());
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
	 * 이미 승격된 전시=스킵, 그 외=draft 스테이징(신규)·갱신(재sync).
	 *
	 * <p><b>레거시 뒤채움은 여기서 하지 않는다</b>: 승격은 {@code ExhibitionRegistrationFacade#register}가 항상
	 * 상세(값 또는 확인 표식)를 함께 쓰므로, 현재 코드로 승격된 전시는 {@code NEEDS_DETAIL}이 될 수 없다 —
	 * 그 상태는 draft 도입 <b>이전</b> 행에만 남는다. 조기 종료로 목록 뒤쪽을 보지 않게 되면서 이 분기는
	 * 어차피 닿지 않으므로, 매 건 상태를 되묻는 대신 뺐다(필요해지면 일회성 배치로 — 사용자 결정).
	 */
	private SyncOutcome syncOne(CatalogExhibitionData data, LocalDateTime now) {
		if (exhibitionBackfill.findDetailTargetState(data.externalId()) == DetailTargetState.ALREADY_SYNCED) {
			return SyncOutcome.SKIPPED;
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
