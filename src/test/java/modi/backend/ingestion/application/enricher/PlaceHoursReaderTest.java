package modi.backend.ingestion.application.enricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import modi.backend.application.exhibition.contract.PlaceHoursTarget;
import modi.backend.domain.exhibition.hours.PlaceHoursData;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;
import modi.backend.ingestion.application.ExhibitionSyncFacade;
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.ExternalApiOutcome;
import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;

/**
 * 영업시간 조회 <b>호출 감사</b> 계약 검증(순수 단위 — Mockito). 감사가 조회기(infra)에서 호출부로 올라오면서
 * 생긴 계약이라 여기서 못박는다.
 * <ul>
 *   <li>구글 실호출은 결과(성공·미발견·실패)에 따라 콜당 1행을 남긴다.</li>
 *   <li>mock 조회기는 외부를 부르지 않으므로 <b>한 행도 남기지 않는다</b>(유령 감사 행 차단).</li>
 *   <li>감사 키는 주소가 아니라 <b>전시장 이름</b>의 정규화 키다(ADR-07 — place_key와 같은 어휘).</li>
 * </ul>
 */
class PlaceHoursReaderTest {

	private PlaceHoursProvider provider;
	private ExhibitionSyncFacade facade;
	private PlaceHoursReader reader;

	private final PlaceHoursTarget target = new PlaceHoursTarget(1L, " 부산현대  미술관 ", "부산 사하구 낙동남로 1191");

	@BeforeEach
	void setUp() {
		provider = Mockito.mock(PlaceHoursProvider.class);
		facade = Mockito.mock(ExhibitionSyncFacade.class);
		reader = new PlaceHoursReader(provider, facade);
	}

	/** reader는 결과를 그대로 흘려보내므로 내용은 무관 — 빈 영업시간 결과 하나면 충분하다. */
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

	@Test
	@DisplayName("구글 조회 성공 → SUCCESS 한 행, 감사 키는 전시장 이름의 정규화 키(주소 아님)")
	void read_google_success_recordsOneRowKeyedByNormalizedPlaceName() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(provider.fetch(target.placeName(), target.placeAddr())).willReturn(Optional.of(anyResult()));

		assertThat(reader.read(target)).isPresent();

		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(facade).recordApiCall(eq(ExternalApi.GOOGLE), key.capture(), eq(ExternalApiOutcome.SUCCESS),
				any(LocalDateTime.class));
		// PlaceKey.of: 앞뒤 공백 제거 + 연속 공백 1개화 — exhibition_place.place_key와 같은 규칙이라야 조인된다.
		assertThat(key.getValue()).isEqualTo("부산현대 미술관");
	}

	@Test
	@DisplayName("구글이 장소를 모르면 NO_DATA — 실패가 아니라 사실이다")
	void read_google_notFound_recordsNoData() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(provider.fetch(any(), any())).willReturn(Optional.empty());

		assertThat(reader.read(target)).isEmpty();

		verify(facade).recordApiCall(eq(ExternalApi.GOOGLE), any(), eq(ExternalApiOutcome.NO_DATA),
				any(LocalDateTime.class));
	}

	@Test
	@DisplayName("전송 오류는 FAILED로 남기고 그대로 전파한다 — 과금은 이미 일어났을 수 있다")
	void read_google_transportError_recordsFailedAndRethrows() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
		given(provider.fetch(any(), any())).willThrow(new RuntimeException("timeout"));

		assertThatThrownBy(() -> reader.read(target)).isInstanceOf(RuntimeException.class).hasMessage("timeout");

		verify(facade).recordApiCall(eq(ExternalApi.GOOGLE), any(), eq(ExternalApiOutcome.FAILED),
				any(LocalDateTime.class));
	}

	@Test
	@DisplayName("mock 조회기는 외부를 부르지 않으므로 감사 행을 남기지 않는다(유령 감사 행 차단)")
	void read_mock_recordsNothing() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.MOCK);
		given(provider.fetch(any(), any())).willReturn(Optional.of(anyResult()));

		assertThat(reader.read(target)).isPresent();

		// 이 게이트가 없으면 mock이 기본인 로컬·CI·develop에서 api=GOOGLE 행이 쌓여 호출량이 거짓이 된다.
		verify(facade, never()).recordApiCall(any(), any(), any(), any());
	}

	@Test
	@DisplayName("조회 결과와 무관하게 조회기의 벤더를 그대로 노출한다(정준층 계보)")
	void vendor_delegatesToProvider() {
		given(provider.vendor()).willReturn(PlaceHoursVendor.MOCK);

		assertThat(reader.vendor()).isEqualTo(PlaceHoursVendor.MOCK);
	}
}
