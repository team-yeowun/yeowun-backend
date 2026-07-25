package modi.backend.ingestion.application.place;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.application.exhibition.contract.PlaceRegistrar;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.domain.exhibition.hours.OpeningHoursFormatter;
import modi.backend.domain.exhibition.hours.PlaceHoursData;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.PlaceKey;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.domain.snapshot.GooglePlaceSnapshot;
import modi.backend.ingestion.infra.snapshot.GooglePlaceSnapshotJpaRepository;

/**
 * 전시장 축의 서비스 — <b>resolve-or-create(코어 계약)·구글 호출(tx 밖)·GOOGLE 콜 감사·반영 tx{구글 스냅샷 +
 * 정준 영업시간}</b>을 맡는다(설계 §3-3). PLACE_STAGED 소비의 실행부다 — 이벤트 발행은 없다(설계 D4로 스윕·재검증
 * 폐기, 발행자는 진행 상태 서비스 하나로 수렴). 다음 스텝 지식도 없다 — 소비 순서는 오케스트레이터의 몫이다.
 *
 * <p><b>유령 감사 행을 막는다</b>: mock 조회기는 외부를 부르지 않으므로 기록하지 않는다. 이 게이트가 없으면
 * mock이 기본인 로컬·CI·develop에서 {@code api=GOOGLE} 행이 쌓여 "구글을 이만큼 불렀다"는 거짓이 남는다.
 *
 * <p>코어 접촉은 계약 경유만이다(ADR-12): 전시장 생성은 {@link PlaceRegistrar}, 정준층 반영은
 * {@link PlaceHoursGateway}. 구글 스냅샷은 자기 축 원장이라 직접 쓴다(§1-2 예외 — 원장·정준이 같은 tx).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionPlaceService {

	private final PlaceHoursProvider placeHoursProvider;
	/** 외부 호출 감사(공용) — REQUIRES_NEW + 삼킴은 Recorder가 진다. */
	private final ExternalApiCallLogRecorder callLogRecorder;
	/** 전시장 생성 계약(코어 소유) — resolve-or-create + 신규/기존 판정. */
	private final PlaceRegistrar placeRegistrar;
	/** 영업시간 정준층 계약(코어 소유) — 반영의 유일한 통로. */
	private final PlaceHoursGateway placeHoursGateway;
	private final GooglePlaceSnapshotJpaRepository googlePlaceSnapshotRepository;
	private final OpeningHoursFormatter openingHoursFormatter;

	/** 이 조회기의 벤더 — 정준층 계보로 남는다(포트 계약 그대로 노출). */
	public PlaceHoursVendor vendor() {
		return placeHoursProvider.vendor();
	}

	/**
	 * 전시장 해소(PLACE_STAGED 소비의 ①·반영) — 코어 계약으로 resolve-or-create(멱등)하고 신규/기존을 판정한다.
	 * 신규({@code created})일 때만 구글 최초 조회 대상이다(장소당 1콜 — 승격이 먼저 만들어뒀어도 "기존"으로
	 * 판정돼 중복 호출이 없다).
	 */
	public PlaceRegistrar.Resolved resolvePlace(CatalogExhibitionData seed) {
		return placeRegistrar.resolveOrCreate(seed.place(), seed.region(), seed.sigungu(), seed.gpsX(), seed.gpsY());
	}

	/**
	 * 한 장소의 영업시간을 조회하고(tx 밖) 그 호출 1건을 감사에 남긴다. 전송 오류는 그대로 전파한다
	 * (이벤트 수명주기는 아웃박스가 잇는다 — D5: 총 3회 시도 후 영구 실패).
	 *
	 * <p>감사 키는 전시장 이름의 정규화 키다({@link PlaceKey}) — {@code exhibition_place.place_key}와 같은
	 * 어휘라야 "이 장소를 몇 번 불렀나"가 조인으로 답해진다. PLACE_STAGED 시점엔 상세(주소)가 아직 없을 수
	 * 있어 이름 기반 질의가 될 수 있다(정확도 트레이드오프 — 설계 D4에서 정책상 수용).
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
	 * 한 전시장의 조회 결과를 반영한다(전시장 단위 트랜잭션): 벤더 원장(구글 스냅샷) 적재 + 우리 표시 규칙
	 * ({@link OpeningHoursFormatter})으로 정준층(place_hours) 파생 반영(계약). {@code result}가 null(미발견)이면
	 * {@code formatted=null}로 값은 비우되 동기화 시각은 남긴다(NO_DATA도 스텝 해소다).
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
		callLogRecorder.record(ExternalApiCallLog.of(ApiCallSource.INGESTION, ExternalApi.GOOGLE,
				PlaceKey.of(target.placeName()), outcome, calledAt));
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
