package modi.backend.ingestion.application.culture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.ingestion.domain.snapshot.CultureDetailSnapshot;
import modi.backend.ingestion.domain.snapshot.CultureListSnapshot;
import modi.backend.ingestion.infra.snapshot.CultureDetailSnapshotJpaRepository;
import modi.backend.ingestion.infra.snapshot.CultureListSnapshotJpaRepository;
import modi.backend.ingestion.properties.CatalogFetchProperties;

/**
 * 문화포털 축 서비스 단위 — 상세 콜 감사(source=INGESTION)·원장 읽기(genreInputOf·catalogDataOf)를 못박는다.
 * 원장 쓰기는 이 서비스에 없다(SnapshotLedger로 이관 — 원장 합류 규칙).
 */
class ExhibitionKoreaCultureServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 1, 0);

	private ExhibitionCatalogClient client;
	private ExternalApiCallLogRecorder recorder;
	private CultureListSnapshotJpaRepository listRepo;
	private CultureDetailSnapshotJpaRepository detailRepo;
	private ExhibitionKoreaCultureService service;

	@BeforeEach
	void setUp() {
		client = mock(ExhibitionCatalogClient.class);
		recorder = mock(ExternalApiCallLogRecorder.class);
		listRepo = mock(CultureListSnapshotJpaRepository.class);
		detailRepo = mock(CultureDetailSnapshotJpaRepository.class);
		service = new ExhibitionKoreaCultureService(client, recorder, listRepo, detailRepo,
				new CatalogFetchProperties(null, null, null, null, null));
	}

	private CultureListSnapshot listSnapshot() {
		CatalogExhibitionData data = new CatalogExhibitionData("EXT-1", "여운전", "국립현대미술관",
				java.time.LocalDate.of(2026, 8, 1), null, ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
				null, "http://detail", null, null, null, "종로구", "미술", "서울");
		return CultureListSnapshot.first(data, NOW);
	}

	@Test
	@DisplayName("fetchDetail — 성공·실패 모두 CULTURE_DETAIL 감사 1행(source=INGESTION), 원장 쓰기는 없다")
	void fetch_detail_audited_both_ways() {
		CultureDetailPayload payload = mock(CultureDetailPayload.class);
		given(client.fetchDetail("EXT-1")).willReturn(payload);

		service.fetchDetail("EXT-1");

		ArgumentCaptor<ExternalApiCallLog> captor = ArgumentCaptor.forClass(ExternalApiCallLog.class);
		then(recorder).should().record(captor.capture());
		assertThat(captor.getValue().getSource()).isEqualTo(ApiCallSource.INGESTION);
		assertThat(captor.getValue().getApi()).isEqualTo(ExternalApi.CULTURE_DETAIL);
		assertThat(captor.getValue().getOutcome()).isEqualTo(ExternalApiOutcome.SUCCESS);
		then(detailRepo).shouldHaveNoInteractions(); // 원장은 반영 tx(SnapshotLedger)의 몫

		willThrow(new RuntimeException("timeout")).given(client).fetchDetail("EXT-2");
		assertThatThrownBy(() -> service.fetchDetail("EXT-2")).isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("genreInputOf — 목록+상세 원장에서 분류 입력을 조립한다(상세 없으면 설명 null, 목록 없으면 empty)")
	void genre_input_from_ledger() {
		given(listRepo.findByExternalId("EXT-1")).willReturn(Optional.of(listSnapshot()));
		CultureDetailPayload payload = mock(CultureDetailPayload.class);
		given(payload.contents1()).willReturn("<p>여름의 여운</p>");
		CultureDetailSnapshot detail = CultureDetailSnapshot.first("EXT-1", payload); // 스터빙 밖에서 생성
		given(detailRepo.findByExternalId("EXT-1")).willReturn(Optional.of(detail));

		GenreClassification input = service.genreInputOf("EXT-1").orElseThrow();
		assertThat(input.title()).isEqualTo("여운전");
		assertThat(input.categoryHint()).isEqualTo("PAINTING");
		assertThat(input.place()).isEqualTo("국립현대미술관");
		assertThat(input.description()).contains("여운").doesNotContain("<p>");

		given(listRepo.findByExternalId("EXT-2")).willReturn(Optional.empty());
		assertThat(service.genreInputOf("EXT-2")).isEmpty();
	}

	@Test
	@DisplayName("catalogDataOf — 목록 원장의 문자열이 타입으로 복원된다(전시장 축 시드 소스)")
	void catalog_data_restored() {
		given(listRepo.findByExternalId("EXT-1")).willReturn(Optional.of(listSnapshot()));

		CatalogExhibitionData data = service.catalogDataOf("EXT-1").orElseThrow();

		assertThat(data.place()).isEqualTo("국립현대미술관");
		assertThat(data.region()).isEqualTo(ExhibitionRegion.SEOUL);
		assertThat(data.startDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
		assertThat(data.detailUrl()).isEqualTo("http://detail");
	}

	@Test
	@DisplayName("fetchPages — 인증키 미설정이면 호출 0으로 빈 결과(외부 API 실호출 없음)")
	void unconfigured_key_skips() {
		given(client.isConfigured()).willReturn(false);

		assertThat(service.fetchPages(NOW)).isEmpty();

		then(client).should(org.mockito.Mockito.never()).fetchPage(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyInt());
	}
}
