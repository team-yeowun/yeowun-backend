package modi.backend.ingestion.application.enricher;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.PlaceKey;
import modi.backend.ingestion.application.ExhibitionSyncFacade;
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.ExternalApiOutcome;
import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;

/**
 * 영업시간 조회 1건 + <b>그 호출의 감사</b>. {@link PlaceHoursEnricher}·{@link PlaceHoursRefresher}가 공유한다.
 *
 * <p><b>왜 조회기(infra)가 아니라 여기인가</b>: 조회기의 책임은 "불러서 응답을 준다"까지다(culture에서 정한 방침).
 * 감사 행의 단위는 <b>한 번의 HTTP 호출</b>인데 영업시간은 1콜=1행이라, 목록(fetchAll 1회 = 3콜)처럼 순회를 올려야
 * 하는 문제가 없다 — 호출부가 그대로 감사의 경계다. 덕분에 {@code GooglePlaceClient}에서 리포지토리가 빠졌다.
 *
 * <p><b>유령 감사 행을 막는다</b>: mock 조회기는 외부를 부르지 않으므로 기록하지 않는다. 이 게이트가 없으면
 * mock이 기본인 로컬·CI·develop에서 {@code api=GOOGLE} 행이 쌓여 "구글을 이만큼 불렀다"는 거짓이 남는다
 * ({@code ExhibitionSyncFacade.archiveGooglePlaceSnapshot}이 스냅샷 적재에서 쓰는 것과 같은 게이트).
 */
@Component
@RequiredArgsConstructor
public class PlaceHoursReader {

	private final PlaceHoursProvider placeHoursProvider;
	private final ExhibitionSyncFacade exhibitionSyncFacade;

	/** 이 조회기의 벤더 — 정준층 계보로 남는다(포트 계약 그대로 노출). */
	public PlaceHoursVendor vendor() {
		return placeHoursProvider.vendor();
	}

	/**
	 * 한 장소의 영업시간을 조회하고 그 호출 1건을 감사에 남긴다. 전송 오류는 실패로 남기고 그대로 전파한다
	 * (호출부가 장소 단위로 스킵·재시도를 판단한다 — 기존 동작 불변).
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
	 * 감사 키는 <b>전시장 이름의 정규화 키</b>다({@link PlaceKey}, ADR-07) — {@code exhibition_place.place_key}와
	 * 같은 어휘라야 "이 장소를 몇 번 불렀나"가 조인으로 답해진다.
	 */
	private void record(PlaceHoursTarget target, ExternalApiOutcome outcome, LocalDateTime calledAt) {
		exhibitionSyncFacade.recordApiCall(ExternalApi.GOOGLE, PlaceKey.of(target.placeName()), outcome, calledAt);
	}
}
