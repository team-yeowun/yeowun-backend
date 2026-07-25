package modi.backend.ingestion.application.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.application.exhibition.contract.PlaceRegistrar;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.domain.exhibition.hours.OpeningHoursFormatter;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.infra.snapshot.GooglePlaceSnapshotJpaRepository;

/**
 * 전시장 축 서비스 단위 — resolve 위임(코어 계약)·mock 감사 게이트·NO_DATA 반영(시각만 남김)을 못박는다.
 * 스윕·재검증은 폐기됐다(D4) — 이 서비스는 이벤트 발행 0인 순수 소비 축이다.
 */
class ExhibitionPlaceServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 1, 0);

	private PlaceHoursProvider provider;
	private ExternalApiCallLogRecorder recorder;
	private PlaceRegistrar placeRegistrar;
	private PlaceHoursGateway gateway;
	private GooglePlaceSnapshotJpaRepository snapshotRepository;
	private ExhibitionPlaceService service;

	@BeforeEach
	void setUp() {
		provider = mock(PlaceHoursProvider.class);
		recorder = mock(ExternalApiCallLogRecorder.class);
		placeRegistrar = mock(PlaceRegistrar.class);
		gateway = mock(PlaceHoursGateway.class);
		snapshotRepository = mock(GooglePlaceSnapshotJpaRepository.class);
		service = new ExhibitionPlaceService(provider, recorder, placeRegistrar, gateway, snapshotRepository,
				new OpeningHoursFormatter());
	}

	@Test
	@DisplayName("resolvePlace — 코어 계약 위임(원시값·코어 enum만 경계를 넘는다)")
	void resolve_delegates_to_contract() {
		CatalogExhibitionData seed = new CatalogExhibitionData("EXT-1", "제목", "국립현대미술관", null, null,
				null, null, null, null, null, 127.0, 37.5, "종로구", null, null);
		given(placeRegistrar.resolveOrCreate("국립현대미술관", null, "종로구", 127.0, 37.5))
				.willReturn(new PlaceRegistrar.Resolved(7L, "국립현대미술관", true));

		PlaceRegistrar.Resolved resolved = service.resolvePlace(seed);

		assertThat(resolved.created()).isTrue();
		assertThat(resolved.exhibitionPlaceId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("read(mock 벤더) — 외부를 부르지 않았으니 감사도 남기지 않는다(유령 감사 금지)")
	void mock_vendor_not_audited() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.MOCK);
		given(provider.fetch(anyString(), any())).willReturn(Optional.empty());

		service.read(new PlaceHoursTarget(7L, "장소", null));

		then(recorder).should(never()).record(any());
	}

	@Test
	@DisplayName("read(구글) — 검색 결과 없음은 실패가 아니라 NO_DATA 사실로 남는다(시도=비용)")
	void google_no_data_audited() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(provider.fetch(anyString(), any())).willReturn(Optional.empty());

		service.read(new PlaceHoursTarget(7L, "장소", null));

		ArgumentCaptor<ExternalApiCallLog> captor = ArgumentCaptor.forClass(ExternalApiCallLog.class);
		then(recorder).should().record(captor.capture());
		assertThat(captor.getValue().getApi()).isEqualTo(ExternalApi.GOOGLE);
		assertThat(captor.getValue().getOutcome()).isEqualTo(ExternalApiOutcome.NO_DATA);
	}

	@Test
	@DisplayName("applyVenueHours(미발견) — 값은 비우되 동기화 시각은 남긴다(NO_DATA도 스텝 해소), 벤더 스냅샷은 없다")
	void apply_no_data_leaves_timestamp() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.MOCK);

		service.applyVenueHours(new PlaceHoursTarget(7L, "장소", null), null, NOW);

		then(gateway).should().applyHours(eq(7L), eq(null), any(PlaceHoursStatus.class),
				eq(PlaceHoursVendor.MOCK), eq(NOW));
		then(snapshotRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("applyVenueHours(구글 응답) — 벤더 스냅샷 upsert + 정준층 반영이 같은 흐름이다(자기 축 원장)")
	void apply_google_result_archives_snapshot() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(snapshotRepository.findByExhibitionPlaceId(7L)).willReturn(Optional.empty());
		PlaceHoursResult result = mock(PlaceHoursResult.class);
		given(result.data()).willReturn(null);

		service.applyVenueHours(new PlaceHoursTarget(7L, "장소", null), result, NOW);

		then(snapshotRepository).should().save(any());
		then(gateway).should().applyHours(eq(7L), eq(null), any(), eq(PlaceHoursVendor.GOOGLE), eq(NOW));
	}
}
