package modi.backend.ingestion.application.culture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.ingestion.application.audit.ExternalApiCallLogRecorder;
import modi.backend.ingestion.domain.ExhibitionRealm;
import modi.backend.ingestion.domain.audit.ExternalApi;
import modi.backend.ingestion.domain.audit.ExternalApiCallLog;
import modi.backend.ingestion.domain.audit.ExternalApiOutcome;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogPage;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.ingestion.infra.culture.KoreaCultureDto;
import modi.backend.ingestion.infra.snapshot.CultureDetailSnapshotJpaRepository;
import modi.backend.ingestion.infra.snapshot.CultureListSnapshotJpaRepository;
import modi.backend.ingestion.properties.CatalogFetchProperties;

/**
 * 문화포털 축 서비스 단위 검증(Mockito) — 이번 리팩토링의 핵심 감사 계약을 못박는다:
 * <ul>
 *   <li><b>목록은 콜 단위 감사</b>(CULTURE_LIST) — 페이지 순회 3콜이 1행으로 뭉개지지 않고, 실패 콜도 남는다.</li>
 *   <li><b>상세 감사 신설</b>(CULTURE_DETAIL, requestKey=external_id) — 재설계 전엔 통째로 누락돼 있었다
 *       (행위 변경 D1). 성공·실패 각 1행.</li>
 *   <li><b>상세 스냅샷은 호출 직후 best-effort</b>(행위 변경 D4) — 스냅샷 적재 실패가 상세 반환을 깨지 않는다.</li>
 *   <li>순회 조기 종료(전량 known)·인증키 미설정 스킵(감사 행 0)도 이 서비스의 몫이다(구 CatalogSynchronizer에서 이동).</li>
 * </ul>
 */
class ExhibitionKoreaCultureServiceTest {

	private ExhibitionCatalogClient catalogClient;
	private ExternalApiCallLogRecorder recorder;
	private CultureListSnapshotJpaRepository listSnapshotRepository;
	private CultureDetailSnapshotJpaRepository detailSnapshotRepository;
	private ExhibitionKoreaCultureService service;

	/** pageSize 2 · maxItems 10 — 순회 조건을 만들 수 있는 소형 기준(요청 정책은 이제 이 축의 설정 소유). */
	private final CatalogFetchProperties fetchProperties =
			new CatalogFetchProperties(ExhibitionRealm.EXHIBITION, null, null, 2, 10);
	private final LocalDateTime now = LocalDateTime.now();

	@BeforeEach
	void setUp() {
		catalogClient = mock(ExhibitionCatalogClient.class);
		recorder = mock(ExternalApiCallLogRecorder.class);
		listSnapshotRepository = mock(CultureListSnapshotJpaRepository.class);
		detailSnapshotRepository = mock(CultureDetailSnapshotJpaRepository.class);
		service = new ExhibitionKoreaCultureService(catalogClient, recorder, listSnapshotRepository,
				detailSnapshotRepository, fetchProperties);
		given(catalogClient.isConfigured()).willReturn(true);
		given(detailSnapshotRepository.findByExternalId(any())).willReturn(Optional.empty());
	}

	private static CatalogExhibitionData data(String externalId) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, "전시 " + externalId, "장소", today.minusDays(1),
				today.plusDays(10), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관",
				null, null, null, "전시", "서울");
	}

	private static CatalogPage page(String... ids) {
		return new CatalogPage(java.util.Arrays.stream(ids).map(ExhibitionKoreaCultureServiceTest::data).toList(), 999);
	}

	private static CultureDetailPayload detail() {
		return new KoreaCultureDto.Detail2Response.Item("SEQ", null, null, null, null, null, null, null, null, null,
				"무료", "<p>설명</p>", null, null, null, null, "서울시 종로구", "PLACE-1");
	}

	private List<ExternalApiCallLog> recordedLogs(int expected) {
		ArgumentCaptor<ExternalApiCallLog> captor = ArgumentCaptor.forClass(ExternalApiCallLog.class);
		verify(recorder, times(expected)).record(captor.capture());
		return captor.getAllValues();
	}

	// ── 목록(CULTURE_LIST) ──────────────────────────────────────────────────────

	@Test
	@DisplayName("fetchPages — 콜마다 CULTURE_LIST 한 행씩 남는다(2콜이 1행으로 뭉개지지 않는다)")
	void fetchPages_콜단위_감사() {
		given(catalogClient.fetchPage(any(), eq(1))).willReturn(page("A1", "A2"));   // 꽉 참 → 다음 페이지
		given(catalogClient.fetchPage(any(), eq(2))).willReturn(page("B1"));         // 덜 참 → 종료
		given(listSnapshotRepository.countByExternalIdIn(any())).willReturn(0L);     // 전량 known 아님

		ExhibitionKoreaCultureService.Fetched fetched = service.fetchPages(now);

		assertThat(fetched.items()).hasSize(3);
		assertThat(fetched.totalCount()).isEqualTo(999); // 첫 응답 값으로 고정
		List<ExternalApiCallLog> logs = recordedLogs(2);
		assertThat(logs).allSatisfy(log -> {
			assertThat(log.getApi()).isEqualTo(ExternalApi.CULTURE_LIST);
			assertThat(log.getOutcome()).isEqualTo(ExternalApiOutcome.SUCCESS);
		});
		assertThat(logs.get(0).getRequestKey()).isEqualTo("realmCode=D000&page=1");
		assertThat(logs.get(1).getRequestKey()).isEqualTo("realmCode=D000&page=2");
	}

	@Test
	@DisplayName("fetchPages — 호출이 실패해도 그 콜은 FAILED로 남고, 수집은 실패로 전파된다")
	void fetchPages_실패도_감사() {
		given(catalogClient.fetchPage(any(), anyInt())).willThrow(new RuntimeException("전송 실패"));

		assertThatThrownBy(() -> service.fetchPages(now)).isInstanceOf(RuntimeException.class);

		ExternalApiCallLog log = recordedLogs(1).get(0);
		assertThat(log.getApi()).isEqualTo(ExternalApi.CULTURE_LIST);
		assertThat(log.getOutcome()).isEqualTo(ExternalApiOutcome.FAILED);
	}

	@Test
	@DisplayName("fetchPages — 인증키 미설정이면 외부를 부르지 않고 빈 결과(감사 행 0·totalCount null)")
	void fetchPages_미설정_스킵() {
		given(catalogClient.isConfigured()).willReturn(false);

		ExhibitionKoreaCultureService.Fetched fetched = service.fetchPages(now);

		assertThat(fetched.items()).isEmpty();
		assertThat(fetched.totalCount()).isNull();
		verify(catalogClient, never()).fetchPage(any(), anyInt());
		verify(recorder, never()).record(any());
	}

	@Test
	@DisplayName("fetchPages — 페이지 전량이 이미 아는 항목이면 다음 페이지를 부르지 않는다(조기 종료)")
	void fetchPages_전량known_조기종료() {
		given(catalogClient.fetchPage(any(), eq(1))).willReturn(page("K1", "K2"));
		given(listSnapshotRepository.countByExternalIdIn(any())).willReturn(2L); // 전량 known

		service.fetchPages(now);

		verify(catalogClient, never()).fetchPage(any(), eq(2));
	}

	// ── 상세(CULTURE_DETAIL — 신설 감사) ────────────────────────────────────────

	@Test
	@DisplayName("fetchDetail 성공 — CULTURE_DETAIL·requestKey=external_id·SUCCESS 한 행 + 원문 스냅샷 보관")
	void fetchDetail_성공_감사와_스냅샷() {
		given(catalogClient.fetchDetail("EXT-1")).willReturn(detail());

		CultureDetailPayload payload = service.fetchDetail("EXT-1");

		assertThat(payload.price()).isEqualTo("무료");
		ExternalApiCallLog log = recordedLogs(1).get(0);
		assertThat(log.getApi()).isEqualTo(ExternalApi.CULTURE_DETAIL);
		assertThat(log.getRequestKey()).isEqualTo("EXT-1");
		assertThat(log.getOutcome()).isEqualTo(ExternalApiOutcome.SUCCESS);
		verify(detailSnapshotRepository).save(any()); // 호출 직후 원문 보관(행위 변경 D4)
	}

	@Test
	@DisplayName("fetchDetail 실패 — FAILED 한 행만 남기고 예외를 그대로 전파한다(스냅샷 없음)")
	void fetchDetail_실패_감사후_전파() {
		given(catalogClient.fetchDetail("EXT-1")).willThrow(new RuntimeException("timeout"));

		assertThatThrownBy(() -> service.fetchDetail("EXT-1")).isInstanceOf(RuntimeException.class);

		ExternalApiCallLog log = recordedLogs(1).get(0);
		assertThat(log.getApi()).isEqualTo(ExternalApi.CULTURE_DETAIL);
		assertThat(log.getRequestKey()).isEqualTo("EXT-1");
		assertThat(log.getOutcome()).isEqualTo(ExternalApiOutcome.FAILED);
		verify(detailSnapshotRepository, never()).save(any());
	}

	@Test
	@DisplayName("fetchDetail — 스냅샷 적재가 실패해도 상세 반환은 깨지지 않는다(원문 보관은 best-effort)")
	void fetchDetail_스냅샷실패_무시() {
		given(catalogClient.fetchDetail("EXT-1")).willReturn(detail());
		given(detailSnapshotRepository.save(any())).willThrow(new RuntimeException("snapshot table down"));

		CultureDetailPayload payload = service.fetchDetail("EXT-1");

		assertThat(payload).isNotNull(); // 부가 기록 실패가 본 흐름(상세 반영)을 막지 않는다
	}
}
