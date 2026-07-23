package modi.backend.ingestion.application.place;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.domain.exhibition.hours.OpeningHoursFormatter;
import modi.backend.domain.exhibition.hours.PlaceHoursData;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.PlaceKey;
import modi.backend.ingestion.application.audit.ExternalApiCallLogRecorder;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.audit.ExternalApi;
import modi.backend.ingestion.domain.audit.ExternalApiCallLog;
import modi.backend.ingestion.domain.audit.ExternalApiOutcome;
import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.domain.snapshot.GooglePlaceSnapshot;
import modi.backend.ingestion.infra.snapshot.GooglePlaceSnapshotJpaRepository;
import modi.backend.ingestion.properties.GooglePlaceProperties;

/**
 * 전시장 영업시간 축의 서비스 — <b>구글 호출(tx 밖)·GOOGLE 콜 로그(place_key, mock 게이트)·반영 tx
 * {구글 스냅샷 + 정준 영업시간}</b>을 맡는다(설계 §5). 다음 스텝 지식은 없다 — 대상 선별·이벤트 소비 순서는
 * {@code ExhibitionIngestionOrchestrator}의 몫이다.
 *
 * <p><b>유령 감사 행을 막는다</b>: mock 조회기는 외부를 부르지 않으므로 기록하지 않는다. 이 게이트가 없으면
 * mock이 기본인 로컬·CI·develop에서 {@code api=GOOGLE} 행이 쌓여 "구글을 이만큼 불렀다"는 거짓이 남는다
 * (스냅샷 적재의 벤더 게이트와 같은 취지).
 *
 * <p>코어 정준층({@code place_hours}) 접촉은 {@link PlaceHoursGateway} 계약 경유만이다(ADR-12) — 코어 리포 직주입 없음.
 */
@Service
@RequiredArgsConstructor
public class ExhibitionPlaceService {

	private static final Logger log = LoggerFactory.getLogger(ExhibitionPlaceService.class);

	private final PlaceHoursProvider placeHoursProvider;
	/** 외부 호출 감사 — REQUIRES_NEW + 삼킴은 Recorder가 진다. */
	private final ExternalApiCallLogRecorder callLogRecorder;
	/** 영업시간 정준층 계약(코어 소유) — 대상 선별·반영·실패 기록의 유일한 통로. */
	private final PlaceHoursGateway placeHoursGateway;
	private final GooglePlaceSnapshotJpaRepository googlePlaceSnapshotRepository;
	private final OpeningHoursFormatter openingHoursFormatter;
	/** 스윕 정책(만료 기준일·실행당 상한) — 이 축의 정책이라 파사드가 아니라 여기가 소유한다(유료 유량 가드). */
	private final GooglePlaceProperties googlePlaceProperties;
	/** 발견한 사실(PLACE_HOURS_STALE)을 싣는 우체국 — 실행은 릴레이가 드레인한다. */
	private final ExhibitionOutboxService exhibitionOutboxService;

	/** 이 조회기의 벤더 — 정준층 계보로 남는다(포트 계약 그대로 노출). */
	public PlaceHoursVendor vendor() {
		return placeHoursProvider.vendor();
	}

	/**
	 * 영업시간이 <b>필요한 장소를 발견해 사실로 발행</b>한다(조회하지 않는다) — 주소가 있는데 아직 한 번도
	 * 못 봤거나(미조회) 확인한 지 오래된(만료) 장소가 대상이고, 그 장소당 {@code PLACE_HOURS_STALE} 한 건을
	 * 멱등 enqueue한다. 실제 조회·반영·재시도는 릴레이가 드레인하며 아웃박스 수명주기(백오프·at-least-once)를 탄다.
	 *
	 * <p><b>왜 여기서 직접 조회하지 않나</b>: 직접 조회하면 이 배치만 아웃박스 밖 특수 경로가 된다 — 실패가
	 * 백오프 없이 "다음 주기까지 대기"로 묻히고, 재시도 상태도 테이블에 안 남는다. 발견(스윕)과 실행(드레인)을
	 * 가르면 실행은 전부 한 메커니즘이 진다.
	 *
	 * <p><b>왜 스윕이 필요한가</b>(이벤트만으로 안 되는 이유): 승격이 발행하는 재검증 이벤트는 "이미 영업시간이
	 * 있는 장소에 새 전시가 들어왔다"만 덮는다. ⓐ 한 번도 조회 안 된 새 전시장과 ⓑ 새 전시가 안 들어오는 조용한
	 * 전시장의 시간 만료는 발행자가 없다 — "시간이 흘렀다"는 아무도 발행해줄 수 없어 선별이 유일한 길이다.
	 *
	 * <p>유료 API라 <b>실행당 상한</b>({@code maxVenuesPerRun})으로 한 회차 발행량을 막는다 — 남은 대상은 다음 회차가 줍는다.
	 */
	public void sweepDueHours(LocalDateTime now) {
		try {
			LocalDateTime staleBefore = now.minusDays(googlePlaceProperties.refreshAfterDays());
			List<PlaceHoursTarget> targets =
					placeHoursGateway.findPlacesNeedingHours(staleBefore, googlePlaceProperties.maxVenuesPerRun());
			for (PlaceHoursTarget target : targets) {
				// 종료된 이전 이벤트는 되살린다 — 영업시간 확인은 한 번으로 끝나는 일이 아니라 반복 사실이다.
				exhibitionOutboxService.enqueueOrReactivate(
						IngestionEventType.PLACE_HOURS_STALE, PlaceKey.of(target.placeName()), now);
			}
			if (!targets.isEmpty()) {
				log.info("영업시간 확인 대상 {}건 발행(조회는 릴레이가 드레인)", targets.size());
			}
		} catch (RuntimeException e) {
			// 영업시간은 부가 기능이라 여기서 터져도 같은 회차의 목록 수집을 깨지 않는다 — 다음 회차가 다시 발견한다.
			// 호출부(오케스트레이터)가 이 판단을 알 필요가 없도록 이 축이 삼킨다.
			log.warn("영업시간 확인 대상 발행 실패(다음 주기 재시도): {}", e.getMessage());
		}
	}

	/**
	 * {@code PLACE_HOURS_STALE} 스텝 한 건 — [대상 해소 → 조회(tx 밖·콜 감사) → 반영(tx)]. 그 장소를 쓰는 전시가
	 * 더는 없으면 할 일 없음으로 조용히 끝난다(멱등 소비).
	 *
	 * <p>조회가 실패하면 <b>정준층에 실패를 남기고 그대로 전파한다</b> — "시도했고 실패했다"를 남기는 건 이 축의
	 * 지식이고(표시값은 지우지 않는다), 이벤트를 어떻게 전이시킬지는 아웃박스 수명주기가 판단한다.
	 */
	public void refreshHours(String placeKey) {
		PlaceHoursTarget target = placeHoursGateway.resolvePlaceHoursTarget(placeKey).orElse(null);
		if (target == null) {
			return;
		}
		try {
			applyVenueHours(target, read(target).orElse(null), LocalDateTime.now());
		} catch (RuntimeException e) {
			markFailure(target.exhibitionPlaceId());
			throw e;
		}
	}

	/** 이벤트 {@code target_key}(=place_key)로 조회 대상을 만든다 — 계약 위임(장소를 쓰는 전시가 없으면 empty). */
	public Optional<PlaceHoursTarget> resolveTarget(String placeKey) {
		return placeHoursGateway.resolvePlaceHoursTarget(placeKey);
	}

	/** 조회 전송 실패를 정준층에 남긴다(재시도 대상 유지) — 계약 위임(기록 실패는 계약이 삼킨다). */
	public void markFailure(Long exhibitionPlaceId) {
		placeHoursGateway.markHoursFailure(exhibitionPlaceId, vendor());
	}

	/**
	 * 한 장소의 영업시간을 조회하고(tx 밖) 그 호출 1건을 감사에 남긴다. 전송 오류는 실패로 남기고 그대로 전파한다
	 * (호출부가 장소 단위로 스킵·재시도를 판단한다 — 기존 동작 불변).
	 *
	 * <p>감사 키는 <b>전시장 이름의 정규화 키</b>다({@link PlaceKey}, ADR-07) — {@code exhibition_place.place_key}와
	 * 같은 어휘라야 "이 장소를 몇 번 불렀나"가 조인으로 답해진다.
	 */
	public Optional<PlaceHoursResult> read(PlaceHoursTarget target) {
		if (vendor() != PlaceHoursVendor.GOOGLE) {
			// 외부 호출이 일어나지 않았다 — 부르지 않은 건은 감사에 남기지 않는다.
			return placeHoursProvider.fetch(target.placeName(), target.placeAddr());
		}
		LocalDateTime calledAt = LocalDateTime.now();
		try {
			Optional<PlaceHoursResult> fetched = placeHoursProvider.fetch(target.placeName(), target.placeAddr());
			// 검색 결과 없음은 실패가 아니라 "구글이 그런 장소를 모른다"는 사실이다.
			record(target, fetched.isPresent() ? ExternalApiOutcome.SUCCESS : ExternalApiOutcome.NO_DATA, calledAt);
			return fetched;
		} catch (RuntimeException e) {
			// 실패해도 과금은 이미 일어났을 수 있다 — 그래서 실패도 한 행으로 남긴다(시도 = 비용).
			record(target, ExternalApiOutcome.FAILED, calledAt);
			throw e;
		}
	}

	/**
	 * 한 전시장의 조회 결과를 반영한다(전시장 단위 트랜잭션): 벤더 원본 적재 + 우리 표시 규칙
	 * ({@link OpeningHoursFormatter})으로 정준층(place_hours) 파생 반영(계약). {@code result}가 null(미발견)이면
	 * {@code formatted=null}로 값은 비우되 동기화 시각은 남긴다(재조회 백오프 — NO_DATA도 스텝 해소다).
	 */
	@Transactional
	public void applyVenueHours(PlaceHoursTarget target, PlaceHoursResult result, LocalDateTime now) {
		Long placeId = target.exhibitionPlaceId();
		archiveGooglePlaceSnapshot(placeId, result, now);
		PlaceHoursData data = result == null ? null : result.data();
		String formatted = data == null ? null : openingHoursFormatter.format(data.weeklyHours());
		placeHoursGateway.applyHours(placeId, formatted, PlaceHoursStatus.of(data, formatted), vendor(), now);
	}

	private void record(PlaceHoursTarget target, ExternalApiOutcome outcome, LocalDateTime calledAt) {
		callLogRecorder.record(ExternalApiCallLog.of(ExternalApi.GOOGLE, PlaceKey.of(target.placeName()),
				outcome, calledAt));
	}

	/** 벤더 스냅샷 upsert — 구글이 준 응답만 적재한다(mock은 정준층에 provider=MOCK으로만 남고 벤더층은 비어 있는 게 정상). */
	private void archiveGooglePlaceSnapshot(Long placeId, PlaceHoursResult result, LocalDateTime now) {
		if (placeId == null || result == null || vendor() != PlaceHoursVendor.GOOGLE) {
			return;
		}
		googlePlaceSnapshotRepository.findByExhibitionPlaceId(placeId)
				.ifPresentOrElse(row -> {
					row.refresh(result, now);
					googlePlaceSnapshotRepository.save(row);
				}, () -> googlePlaceSnapshotRepository.save(
						GooglePlaceSnapshot.first(placeId, result, now)));
	}
}
