package modi.backend.ingestion.application.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import modi.backend.application.exhibition.contract.PlaceHoursGateway;
import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.domain.exhibition.hours.OpeningHoursFormatter;
import modi.backend.domain.exhibition.hours.PlaceHoursData;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;
import modi.backend.ingestion.application.audit.ExternalApiCallLogRecorder;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.domain.audit.ExternalApi;
import modi.backend.ingestion.domain.audit.ExternalApiCallLog;
import modi.backend.ingestion.domain.audit.ExternalApiOutcome;
import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.ingestion.infra.snapshot.GooglePlaceSnapshotJpaRepository;
import modi.backend.ingestion.properties.GooglePlaceProperties;

/**
 * 전시장 영업시간 축 서비스 단위 검증(Mockito) — 구 PlaceHoursReaderTest의 <b>호출 감사</b> 계약이 이 서비스로
 * 흡수되면서 옮겨 온 테스트다:
 * <ul>
 *   <li>구글 실호출은 결과(성공·미발견·실패)에 따라 GOOGLE 콜당 1행을 남긴다. 미발견은 실패가 아니라 NO_DATA.</li>
 *   <li>mock 조회기는 외부를 부르지 않으므로 <b>한 행도 남기지 않는다</b>(유령 감사 행 차단 — mock이 기본인
 *       로컬·CI·develop에서 api=GOOGLE 행이 쌓이면 호출량이 거짓이 된다).</li>
 *   <li>감사 키는 주소가 아니라 <b>전시장 이름</b>의 정규화 키다(ADR-07 — place_key와 같은 어휘라야 조인된다).</li>
 *   <li>NO_DATA(result=null)도 스텝 해소다 — formatted=null로 정준 반영해 synced_at(재조회 백오프 기준)을 남긴다.</li>
 * </ul>
 */
class ExhibitionPlaceServiceTest {

	private PlaceHoursProvider provider;
	private ExternalApiCallLogRecorder recorder;
	private PlaceHoursGateway gateway;
	private GooglePlaceSnapshotJpaRepository snapshotRepository;
	private ExhibitionOutboxService outboxService;
	private ExhibitionPlaceService service;

	private final PlaceHoursTarget target = new PlaceHoursTarget(1L, " 부산현대  미술관 ", "부산 사하구 낙동남로 1191");

	@BeforeEach
	void setUp() {
		provider = mock(PlaceHoursProvider.class);
		recorder = mock(ExternalApiCallLogRecorder.class);
		gateway = mock(PlaceHoursGateway.class);
		snapshotRepository = mock(GooglePlaceSnapshotJpaRepository.class);
		outboxService = mock(ExhibitionOutboxService.class);
		service = new ExhibitionPlaceService(provider, recorder, gateway, snapshotRepository,
				new OpeningHoursFormatter(), new GooglePlaceProperties(null, null, null, null, null, null, null, null),
				outboxService);
	}

	/** 서비스는 결과를 그대로 흘려보내므로 내용은 무관 — 빈 영업시간 결과 하나면 충분하다. */
	private static PlaceHoursResult anyResult() {
		return new PlaceHoursResult() {
			public PlaceHoursData data() {
				return new PlaceHoursData(WeeklyOpeningHours.empty());
			}

			public String placeId() {
				return null;
			}

			public String displayNameText() {
				return null;
			}

			public String formattedAddress() {
				return null;
			}

			public String regularOpeningHoursJson() {
				return null;
			}
		};
	}

	private ExternalApiCallLog recordedLog() {
		ArgumentCaptor<ExternalApiCallLog> captor = ArgumentCaptor.forClass(ExternalApiCallLog.class);
		verify(recorder).record(captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("구글 조회 성공 → SUCCESS 한 행, 감사 키는 전시장 이름의 정규화 키(주소 아님)")
	void read_google_success_recordsOneRowKeyedByNormalizedPlaceName() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(provider.fetch(target.placeName(), target.placeAddr())).willReturn(Optional.of(anyResult()));

		assertThat(service.read(target)).isPresent();

		ExternalApiCallLog log = recordedLog();
		assertThat(log.getApi()).isEqualTo(ExternalApi.GOOGLE);
		assertThat(log.getOutcome()).isEqualTo(ExternalApiOutcome.SUCCESS);
		// PlaceKey.of: 앞뒤 공백 제거 + 연속 공백 1개화 — exhibition_place.place_key와 같은 규칙이라야 조인된다.
		assertThat(log.getRequestKey()).isEqualTo("부산현대 미술관");
	}

	@Test
	@DisplayName("구글이 장소를 모르면 NO_DATA — 실패가 아니라 사실이다")
	void read_google_notFound_recordsNoData() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(provider.fetch(any(), any())).willReturn(Optional.empty());

		assertThat(service.read(target)).isEmpty();

		assertThat(recordedLog().getOutcome()).isEqualTo(ExternalApiOutcome.NO_DATA);
	}

	@Test
	@DisplayName("전송 오류는 FAILED로 남기고 그대로 전파한다 — 과금은 이미 일어났을 수 있다")
	void read_google_transportError_recordsFailedAndRethrows() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(provider.fetch(any(), any())).willThrow(new RuntimeException("timeout"));

		assertThatThrownBy(() -> service.read(target)).isInstanceOf(RuntimeException.class).hasMessage("timeout");

		assertThat(recordedLog().getOutcome()).isEqualTo(ExternalApiOutcome.FAILED);
	}

	@Test
	@DisplayName("mock 조회기는 외부를 부르지 않으므로 감사 행을 남기지 않는다(유령 감사 행 차단)")
	void read_mock_recordsNothing() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.MOCK);
		given(provider.fetch(any(), any())).willReturn(Optional.of(anyResult()));

		assertThat(service.read(target)).isPresent();

		verify(recorder, never()).record(any());
	}

	@Test
	@DisplayName("조회 결과와 무관하게 조회기의 벤더를 그대로 노출한다(정준층 계보)")
	void vendor_delegatesToProvider() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.MOCK);

		assertThat(service.vendor()).isEqualTo(PlaceHoursVendor.MOCK);
	}

	@Test
	@DisplayName("스윕은 조회하지 않고 발행만 한다 — 미조회·만료 장소당 PLACE_HOURS_STALE 1건(키는 정규화 이름)")
	void sweepDueHours_enqueuesWithoutFetching() {
		given(gateway.findPlacesNeedingHours(any(), anyInt())).willReturn(List.of(target));

		service.sweepDueHours(LocalDateTime.now());

		// 실행(조회·과금)은 릴레이가 드레인하며 아웃박스 수명주기를 탄다 — 스윕은 발견까지다.
		verify(provider, never()).fetch(any(), any());
		verify(outboxService).enqueueOrReactivate(eq(IngestionEventType.PLACE_HOURS_STALE), eq("부산현대 미술관"), any());
	}

	@Test
	@DisplayName("refreshHours — 그 장소를 쓰는 전시가 더는 없으면 외부 호출 없이 조용히 끝난다(멱등 소비)")
	void refreshHours_targetGone_noFetch() {
		given(gateway.resolvePlaceHoursTarget("사라진장소")).willReturn(Optional.empty());

		service.refreshHours("사라진장소");

		verify(provider, never()).fetch(any(), any());
		verify(gateway, never()).applyHours(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("refreshHours — NO_DATA(미발견)도 스텝 해소: formatted=null로 반영해 synced_at을 남긴다")
	void refreshHours_noData_stillApplies() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(gateway.resolvePlaceHoursTarget("부산현대 미술관")).willReturn(Optional.of(target));
		given(provider.fetch(any(), any())).willReturn(Optional.empty()); // 구글이 장소를 모른다

		service.refreshHours("부산현대 미술관");

		verify(gateway).applyHours(eq(1L), isNull(), eq(PlaceHoursStatus.NOT_FOUND), eq(PlaceHoursVendor.GOOGLE), any());
	}

	@Test
	@DisplayName("refreshHours — 조회 실패는 정준층에 남기고(markFailure) 그대로 전파한다(이벤트 전이는 아웃박스 몫)")
	void refreshHours_fetchFails_marksFailureAndRethrows() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(gateway.resolvePlaceHoursTarget("부산현대 미술관")).willReturn(Optional.of(target));
		given(provider.fetch(any(), any())).willThrow(new RuntimeException("timeout"));

		assertThatThrownBy(() -> service.refreshHours("부산현대 미술관")).hasMessage("timeout");

		verify(gateway).markHoursFailure(eq(1L), eq(PlaceHoursVendor.GOOGLE));
	}

	@Test
	@DisplayName("스윕 실패는 삼킨다 — 영업시간은 부가 기능이라 같은 회차의 목록 수집을 깨지 않는다")
	void sweepDueHours_swallowsFailure() {
		given(gateway.findPlacesNeedingHours(any(), anyInt())).willThrow(new RuntimeException("db down"));

		assertThatCode(() -> service.sweepDueHours(LocalDateTime.now())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("applyVenueHours(null) — 미발견도 스텝 해소다: formatted=null로 정준 반영해 synced_at을 남긴다")
	void applyVenueHours_noData_resolvesStep() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		LocalDateTime now = LocalDateTime.now();

		service.applyVenueHours(target, null, now);

		// 값은 비우되 "확인했다"는 남는다 — 재조회 백오프의 기준. 스냅샷은 원문이 없으니 적재하지 않는다.
		verify(gateway).applyHours(eq(1L), isNull(), eq(PlaceHoursStatus.NOT_FOUND),
				eq(PlaceHoursVendor.GOOGLE), eq(now));
		verify(snapshotRepository, never()).save(any());
	}
}
