package modi.backend.ingestion.application;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.ExhibitionBackfill;
import modi.backend.application.exhibition.contract.PlaceHoursBackfill;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.domain.exhibition.hours.PlaceHoursData;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxFacade;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.ExternalApiOutcome;
import modi.backend.ingestion.domain.entity.CultureDetailSnapshot;
import modi.backend.ingestion.domain.entity.ExternalApiCallLog;
import modi.backend.ingestion.domain.data.GooglePlaceVendorItem;
import modi.backend.ingestion.domain.entity.CultureListSnapshot;
import modi.backend.ingestion.domain.entity.GooglePlaceSnapshot;
import modi.backend.ingestion.domain.entity.IngestionRun;
import modi.backend.ingestion.domain.port.IngestionRunRepository;
import modi.backend.ingestion.infra.CultureListSnapshotJpaRepository;
import modi.backend.ingestion.infra.GooglePlaceSnapshotJpaRepository;

/**
 * 전시 수집·보강 파이프라인의 <b>수집 측 DB 경계</b> — 동기화 루프({@link CatalogSynchronizer})와 아웃박스
 * 처리기(enricher)가 트랜잭션 밖에서 외부 호출을 마친 뒤, 여기 트랜잭션 메서드로 반영을 위임한다.
 *
 * <p><b>코어 접촉은 계약 경유(ADR-12)</b>: 코어 정준층 쓰기는 {@link ExhibitionBackfill}·{@link PlaceHoursBackfill}
 * 인터페이스로만 한다(REQUIRED 전파로 같은 트랜잭션에 합류) — 코어 리포 직주입 없음. 이 파사드가 직접 만지는 건
 * 수집 소유물(벤더 스냅샷·ingestion_run)뿐이고, 복합 반영(코어 반영+원본 보관+후속 enqueue)의 트랜잭션 경계를 제공한다.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionSyncFacade {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionSyncFacade.class);

	/** 레거시 전시 뒤채움 계약(코어 소유) — 상세/장르 정준 반영의 유일한 통로. */
	private final ExhibitionBackfill exhibitionBackfill;
	/** 영업시간 정준층 계약(코어 소유) — place_hours 반영의 유일한 통로. */
	private final PlaceHoursBackfill placeHoursBackfill;
	private final GooglePlaceSnapshotJpaRepository googlePlaceSnapshotRepository;
	private final CultureListSnapshotJpaRepository cultureListSnapshotRepository;
	private final modi.backend.ingestion.infra.CultureDetailSnapshotJpaRepository cultureDetailSnapshotRepository;
	private final IngestionRunRepository ingestionRunRepository;
	/** 외부 호출 감사(append-only) — 저장은 REQUIRES_NEW라 호출자 트랜잭션과 생사를 같이하지 않는다. */
	private final modi.backend.ingestion.domain.port.ExternalApiCallLogRepository externalApiCallLogRepository;
	/** 전시 아웃박스 — 상태 변경과 같은 트랜잭션에서 후속 메시지를 남긴다(at-least-once의 진입점). */
	private final ExhibitionOutboxFacade exhibitionOutboxFacade;

	/**
	 * FETCH_DETAIL 레거시 반영 — [코어 상세 반영(계약) + 원본 벤더층 보관 + 영업시간 재검증 enqueue]가
	 * <b>한 트랜잭션</b>이다(ADR-10 원자성 — 상세는 반영됐는데 재검증 메시지가 유실되는 창이 없다).
	 * 대상이 아니면(전시 없음·이미 완성) 원본 보관·enqueue도 생략한다(기존 동작 보존).
	 */
	@Transactional
	public void applyLegacyDetail(String externalId, CultureDetailPayload payload) {
		LocalDateTime now = LocalDateTime.now();
		ExhibitionBackfill.DetailApplied applied = exhibitionBackfill.applyDetail(externalId, payload.toDetail(), now);
		if (!applied.applied()) {
			return;
		}
		archiveDetailSnapshot(externalId, payload);
		if (applied.placeKey() != null) {
			exhibitionOutboxFacade.enqueueHoursRefresh(applied.placeKey(), now);
		}
	}

	/**
	 * 한 전시장의 조회 결과를 반영한다(전시장 단위 트랜잭션): 벤더 원본 적재 + 정준층(place_hours) 반영(계약).
	 * {@code data}가 null(미발견)이면 {@code formatted=null}로 값은 비우되 동기화 시각은 남긴다(재조회 백오프).
	 */
	@Transactional
	public void applyVenueHours(PlaceHoursTarget target, PlaceHoursData data, GooglePlaceVendorItem vendorItem,
			String formatted, PlaceHoursVendor vendor, LocalDateTime now) {
		Long placeId = target.exhibitionPlaceId();
		archiveGooglePlaceSnapshot(placeId, vendorItem, vendor, now);
		placeHoursBackfill.applyHours(placeId, formatted, PlaceHoursStatus.of(data, formatted), vendor, now);
	}

	/**
	 * 외부 호출 1건을 감사에 남긴다(append-only). 부가 기록이라 실패해도 수집을 깨지 않는다.
	 * <p>
	 * 순회가 여기(application)에 있으므로 <b>콜 단위로</b> 남는다 — 어댑터가 순회를 끌어안던 시절엔
	 * 3콜이 1행으로 뭉개졌다.
	 */
	public void recordApiCall(ExternalApi api, String requestKey, ExternalApiOutcome outcome, LocalDateTime calledAt) {
		try {
			externalApiCallLogRepository.save(ExternalApiCallLog.of(api, requestKey, outcome, calledAt));
		} catch (RuntimeException e) {
			log.warn("외부 호출 감사 기록 실패(무시): {}", e.getMessage());
		}
	}

	/** 런 감사 기록 — 부가 기록이라 실패해도 동기화 결과를 깨지 않는다. */
	public void archiveIngestionRun(IngestionRun run, int inserted, int completed, int skipped, int deferred) {
		try {
			run.finished(inserted, completed, skipped, deferred, LocalDateTime.now());
			ingestionRunRepository.save(run);
		} catch (RuntimeException e) {
			log.warn("동기화 실행 기록 실패(동기화는 계속): {}", e.getMessage());
		}
	}

	/**
	 * 이 식별자들이 <b>전부</b> 이미 스냅샷에 있는가 — 목록 순회 조기 종료 판정({@code CatalogPageStop}).
	 *
	 * <p><b>왜 {@code exhibitions}가 아니라 스냅샷 기준인가</b>: 스냅샷이 가장 넓은 집합이다. 승격 전 draft 단계
	 * 항목도, 기간 불량으로 도메인이 적재하지 않은 항목도 여기엔 행이 있다. {@code exhibitions}로 판정하면 그것들이
	 * 매 동기화마다 "모르는 것"으로 잡혀 조기 종료가 사실상 걸리지 않는다.
	 *
	 * <p>조회는 <b>페이지당 1회</b>({@code IN})다 — 건당 물으면 아끼려던 쿼리를 판정에서 도로 쓴다.
	 */
	@Transactional(readOnly = true)
	public boolean allSnapshotted(List<String> externalIds) {
		if (externalIds.isEmpty()) {
			return false;
		}
		Set<String> distinct = new HashSet<>(externalIds);
		return cultureListSnapshotRepository.countByExternalIdIn(distinct) == distinct.size();
	}

	/** 벤더 목록 스냅샷 upsert(필드 적재 — ADR-13). 부가 기록이라 실패해도 동기화를 깨지 않는다(이 행 스냅샷만 누락). */
	public void archiveListSnapshot(CatalogExhibitionData data, LocalDateTime syncedAt) {
		try {
			cultureListSnapshotRepository.findByExternalId(data.externalId())
					.ifPresentOrElse(row -> {
						row.seenAgain(data, syncedAt);
						cultureListSnapshotRepository.save(row);
					}, () -> cultureListSnapshotRepository.save(
							CultureListSnapshot.first(data, syncedAt)));
		} catch (RuntimeException e) {
			log.warn("목록 스냅샷 적재 실패(externalId={}, 동기화는 계속): {}", data.externalId(), e.getMessage());
		}
	}

	/** 상세 스냅샷 upsert(응답 원문 그대로 — ADR-13). 부가 기록이라 실패해도 반영을 깨지 않는다. */
	private void archiveDetailSnapshot(String externalId, CultureDetailPayload payload) {
		try {
			cultureDetailSnapshotRepository.findByExternalId(externalId)
					.ifPresentOrElse(row -> {
						row.refresh(payload);
						cultureDetailSnapshotRepository.save(row);
					}, () -> cultureDetailSnapshotRepository.save(
							CultureDetailSnapshot.first(externalId, payload)));
		} catch (RuntimeException e) {
			log.warn("상세 스냅샷 적재 실패(externalId={}, 동기화는 계속): {}", externalId, e.getMessage());
		}
	}

	/** 벤더 스냅샷 upsert — 구글이 준 응답만 적재한다(mock은 정준층에 provider=MOCK으로만 남고 벤더층은 비어 있는 게 정상). */
	private void archiveGooglePlaceSnapshot(Long placeId, GooglePlaceVendorItem vendorItem, PlaceHoursVendor vendor,
			LocalDateTime now) {
		if (placeId == null || vendorItem == null || vendor != PlaceHoursVendor.GOOGLE) {
			return;
		}
		googlePlaceSnapshotRepository.findByExhibitionPlaceId(placeId)
				.ifPresentOrElse(row -> {
					row.refresh(vendorItem, now);
					googlePlaceSnapshotRepository.save(row);
				}, () -> googlePlaceSnapshotRepository.save(
						GooglePlaceSnapshot.first(placeId, vendorItem, now)));
	}

}
