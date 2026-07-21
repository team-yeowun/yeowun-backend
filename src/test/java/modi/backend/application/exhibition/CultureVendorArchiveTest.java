package modi.backend.application.exhibition;

import modi.backend.ingestion.application.CatalogSynchronizer;
import modi.backend.ingestion.application.enricher.GenreEnricher;
import modi.backend.ingestion.application.enricher.DraftPromoter;
import modi.backend.ingestion.application.enricher.DetailEnricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.exhibition.catalog.CatalogDetailData;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CatalogVendorItem;
import modi.backend.ingestion.domain.data.DetailFetch;
import modi.backend.ingestion.domain.data.CatalogListData;
import modi.backend.ingestion.domain.entity.CultureDetailSnapshot;
import modi.backend.ingestion.infra.CultureDetailSnapshotJpaRepository;
import modi.backend.ingestion.domain.entity.CultureListSnapshot;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.OutboxMessageType;
import modi.backend.ingestion.infra.CultureListSnapshotJpaRepository;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionErrorCode;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.support.error.CoreException;

/**
 * 벤더층 <b>원본 적재 병행</b>(이관 3단계) 통합 검증(@SpringBootTest + Testcontainers-MySQL).
 * <p>
 * 이 테스트가 필요한 이유는 2단계-a(쓰기 이중화)와 같다: 이 단계는 <b>읽기를 바꾸지 않으므로</b> 설계상 기존 테스트
 * 전부에 보이지 않는다 — 원본 적재가 통째로 no-op가 돼도 응답도 exhibitions도 그대로라 다른 테스트는 전부 green이다.
 * 즉 "새 테이블에 실제로 쓰였는가"를 보는 테스트가 없으면 이 단계는 검증되지 않은 채로 남는다.
 * <p>
 * 함께 지키는 것: 원본 적재가 <b>기존 동기화 동작을 바꾸지 않아야</b> 한다(적재 건수·상세 완성·기간 스킵·실패 연기).
 * 그래서 매 경로에서 벤더층과 도메인층을 함께 단언한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class CultureVendorArchiveTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	CatalogSynchronizer catalogSynchronizer;

	@Autowired
	DetailEnricher detailEnricher;

	@Autowired
	GenreEnricher genreEnricher;

	@Autowired
	DraftPromoter draftPromoter;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	modi.backend.infra.exhibition.catalog.ExhibitionDetailJpaRepository exhibitionDetailRepository;

	@Autowired
	CultureListSnapshotJpaRepository cultureListSnapshotRepository;

	@Autowired
	CultureDetailSnapshotJpaRepository cultureDetailSnapshotRepository;

	@Autowired
	OutboxMessageRepository outboxMessageRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("동기화 — 목록 원본이 payload·해시와 함께 벤더층에 적재된다(도메인 적재와 병행)")
	void syncCatalog_목록원본_적재() {
		String externalId = nextId();
		CatalogVendorItem vendor = listVendor(externalId, "원본 적재 전시");
		given(exhibitionCatalogClient.fetchAll(any())).willReturn(listData(List.of(listData(externalId, "원본 적재 전시", vendor))));
		given(exhibitionCatalogClient.fetchDetailSnapshot(externalId)).willReturn(Optional.empty());

		int staged = catalogSynchronizer.syncCatalog();
		drainPipeline(); // 상세 해소 → 장르 분류 → 승격(ADR-10 — 전시는 게이트를 채워야 나타난다)

		// 도메인층 — 파이프라인을 다 태우면 기존처럼 전시가 나타난다.
		assertThat(staged).isEqualTo(1);
		assertThat(exhibitionRepository.findByExternalId(externalId)).isPresent();
		// 벤더층 — 이번 단계에서 새로 쓰기 시작한 곳. payload는 응답 원본(매핑 JSON)이 그대로 실려야 한다.
		CultureListSnapshot row = listRow(externalId);
		assertThat(row.getTitle()).isEqualTo("원본 적재 전시"); // 스냅샷 필드가 원문 그대로 남는다(ADR-13)
		assertThat(row.getPlace()).isEqualTo("시립미술관");
		assertThat(row.getPayloadHash()).hasSize(64);
		assertThat(row.getFirstSeenAt()).isNotNull();
		assertThat(row.getLastSeenAt()).isEqualTo(row.getFirstSeenAt());
	}

	@Test
	@DisplayName("재동기화(값 동일) — 행을 늘리지 않고 last_seen_at만 갱신한다(UK 멱등, first_seen_at 보존)")
	void syncCatalog_재동기화_멱등() {
		String externalId = nextId();
		CatalogVendorItem vendor = listVendor(externalId, "멱등 전시");
		given(exhibitionCatalogClient.fetchAll(any())).willReturn(listData(List.of(listData(externalId, "멱등 전시", vendor))));
		given(exhibitionCatalogClient.fetchDetailSnapshot(externalId)).willReturn(Optional.empty());
		catalogSynchronizer.syncCatalog();
		CultureListSnapshot first = listRow(externalId);
		java.time.LocalDateTime firstSeen = first.getFirstSeenAt();
		String firstHash = first.getPayloadHash();

		catalogSynchronizer.syncCatalog();

		CultureListSnapshot row = listRow(externalId); // 조회 자체가 UK 1행을 전제한다(2행이면 여기서 깨진다)
		assertThat(row.getId()).isEqualTo(first.getId()); // 새 행이 아니라 같은 행
		assertThat(row.getFirstSeenAt()).isEqualTo(firstSeen); // 처음 본 시각은 불변
		assertThat(row.getPayloadHash()).isEqualTo(firstHash); // 값이 같으니 해시도 그대로
		assertThat(row.getLastSeenAt()).isAfterOrEqualTo(firstSeen); // "이번에도 있었다"는 갱신된다
	}

	@Test
	@DisplayName("원천이 값을 정정하면 payload와 해시가 갱신된다(행 단위 변경 감지)")
	void syncCatalog_원천정정_payload갱신() {
		String externalId = nextId();
		CatalogVendorItem before = listVendor(externalId, "정정 전 제목");
		given(exhibitionCatalogClient.fetchAll(any())).willReturn(listData(List.of(listData(externalId, "정정 전 제목", before))));
		given(exhibitionCatalogClient.fetchDetailSnapshot(externalId)).willReturn(Optional.empty());
		catalogSynchronizer.syncCatalog();
		String hashBefore = listRow(externalId).getPayloadHash();

		// 원천이 같은 external_id의 내용을 고쳐 보냈다.
		CatalogVendorItem after = listVendor(externalId, "정정 후 제목");
		given(exhibitionCatalogClient.fetchAll(any())).willReturn(listData(List.of(listData(externalId, "정정 후 제목", after))));

		catalogSynchronizer.syncCatalog();

		CultureListSnapshot row = listRow(externalId);
		assertThat(row.getTitle()).isEqualTo("정정 후 제목"); // 필드가 정정값으로 덮인다
		assertThat(row.getPayloadHash()).isNotEqualTo(hashBefore);
	}

	@Test
	@DisplayName("기간이 불량해 도메인 적재에서 스킵되는 항목도 원본은 남긴다(원본은 도메인 유효성과 무관하다)")
	void syncCatalog_기간불량도_원본은_적재() {
		String externalId = nextId();
		CatalogVendorItem vendor = listVendor(externalId, "역전 기간 전시");
		LocalDate today = LocalDate.now();
		// 종료일 < 시작일 — 도메인 불변식 위반이라 exhibitions엔 적재되지 않는다.
		given(exhibitionCatalogClient.fetchAll(any())).willReturn(listData(List.of(new CatalogExhibitionData(externalId,
				"역전 기간 전시", "장소", today, today.minusDays(1), ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING,
				null, null, "기관", null, null, null, "전시", "서울", vendor))));

		int inserted = catalogSynchronizer.syncCatalog();

		assertThat(inserted).isZero();
		assertThat(exhibitionRepository.findByExternalId(externalId)).isEmpty(); // 도메인은 스킵
		assertThat(listRow(externalId).getTitle()).isEqualTo("역전 기간 전시"); // 원천이 뭐라고 했는지는 남는다
	}

	@Test
	@DisplayName("상세 있음 — 원본(payload)만 벤더층에 보관된다(순수 원본 보관소로 회귀)")
	void syncCatalog_상세있음_원본보관() {
		String externalId = nextId();
		CatalogVendorItem detailVendor = detailVendor(externalId, "무료");
		given(exhibitionCatalogClient.fetchAll(any()))
				.willReturn(listData(List.of(listData(externalId, "상세 있는 전시", listVendor(externalId, "상세 있는 전시")))));
		given(exhibitionCatalogClient.fetchDetailSnapshot(externalId)).willReturn(Optional.of(detailData(detailVendor)));

		catalogSynchronizer.syncCatalog();
		detailEnricher.enrichDetails(); // 상세는 아웃박스 릴레이 경로에서 조회·적재된다(sync는 목록 외 호출 0)

		// 상태머신(status/attempt/next_attempt)은 exhibition_outbox으로 이관됐다 — 벤더 테이블엔 무손실 원본만 남는다.
		CultureDetailSnapshot row = detailRow(externalId);
		assertThat(row.getPrice()).isEqualTo("무료");
		assertThat(row.getContents()).isEqualTo("<p>설명</p>"); // HTML 원문 보존(재추출 원료)
	}

	@Test
	@DisplayName("원천에 상세 없음 — 남길 원본이 없어 벤더 행을 만들지 않는다(그 사실은 detail_synced_at이 안다)")
	void syncCatalog_상세없음_행없음() {
		String externalId = nextId();
		given(exhibitionCatalogClient.fetchAll(any()))
				.willReturn(listData(List.of(listData(externalId, "상세 없는 전시", listVendor(externalId, "상세 없는 전시")))));
		given(exhibitionCatalogClient.fetchDetailSnapshot(externalId)).willReturn(Optional.empty());

		catalogSynchronizer.syncCatalog();
		drainPipeline(); // 무상세 확인도 스텝 해소 → 승격까지 이어진다(영구 미승격 방지)

		// 빈 응답은 보관할 원본이 없다 — 벤더 행 없음(V29에서 상태머신 제거). 도메인은 "확인 완료"만 표기해 재조회를 막는다
		// — 상세 satellite 행 존재로 판정한다(연관 부재 = 미동기화).
		assertThat(cultureDetailSnapshotRepository.findByExternalId(externalId)).isEmpty();
		Long exhibitionId = exhibitionRepository.findByExternalId(externalId).orElseThrow().getId();
		assertThat(exhibitionDetailRepository.existsByExhibitionId(exhibitionId)).isTrue();
	}

	@Test
	@DisplayName("상세 호출 실패 — 메시지가 RETRYABLE로 남아 durable 재시도되고, 전시는 게이트 전이라 나타나지 않는다")
	void syncCatalog_상세실패_메시지재시도() {
		String externalId = nextId();
		given(exhibitionCatalogClient.fetchAll(any()))
				.willReturn(listData(List.of(listData(externalId, "상세 실패 전시", listVendor(externalId, "상세 실패 전시")))));
		given(exhibitionCatalogClient.fetchDetailSnapshot(externalId))
				.willThrow(new CoreException(ExhibitionErrorCode.EXTERNAL_API_UNAVAILABLE, "외부 전시 API 호출 실패"));

		int staged = catalogSynchronizer.syncCatalog();
		// 스테이징은 성공하고(목록 외 외부 호출 0 — 상세 실패가 sync를 못 막는다), FETCH_DETAIL 메시지가 함께 남는다.
		assertThat(staged).isEqualTo(1);
		OutboxMessage message = outboxMessageRepository
				.findByMessageTypeAndTargetKey(OutboxMessageType.FETCH_DETAIL, externalId).orElseThrow();
		assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);

		detailEnricher.enrichDetails(); // 드레인에서 상세 조회가 실패한다

		// 실패는 아웃박스 상태로 남고(RETRYABLE — 회복 후 재시도), 전시는 게이트를 못 채워 나타나지 않는다.
		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(OutboxMessageType.FETCH_DETAIL, externalId)
				.orElseThrow().getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE);
		assertThat(exhibitionRepository.findByExternalId(externalId)).isEmpty();
		// 벤더 테이블엔 실패 원본을 남기지 않는다(순수 원본 보관소). 목록 원본은 상세와 무관하게 남는다.
		assertThat(cultureDetailSnapshotRepository.findByExternalId(externalId)).isEmpty();
		assertThat(listRow(externalId)).isNotNull();
	}

	@Test
	@DisplayName("payload가 없으면(조각을 신뢰할 수 없는 응답) 원본을 적재하지 않는다 — 오염된 조각을 남기지 않는다")
	void syncCatalog_payload없으면_적재안함() {
		String externalId = nextId();
		given(exhibitionCatalogClient.fetchAll(any())).willReturn(listData(List.of(listData(externalId, "조각 없는 전시", null))));
		given(exhibitionCatalogClient.fetchDetailSnapshot(externalId)).willReturn(Optional.empty());

		int inserted = catalogSynchronizer.syncCatalog();

		assertThat(inserted).isEqualTo(1); // 도메인 적재는 정상 진행
		assertThat(cultureListSnapshotRepository.findByExternalId(externalId)).isEmpty();
	}

	/**
	 * 목록 수집 결과 래퍼 — 포트가 이제 "원천이 말한 총 건수·절단 여부"까지 돌려준다(이관 5단계, ingestion_run이 채울 값).
	 * 이 테스트들의 관심사가 아니라 아이템만 담고 totalCount는 수집 수와 같게 둔다(= 절단 없음).
	 */
	private static CatalogListData listData(java.util.List<CatalogExhibitionData> items) {
		return new CatalogListData(items, items.size(), false);
	}

	private String nextId() {
		return "VENDOR-" + SEQ.getAndIncrement();
	}

	/** 아웃박스 드레인으로 파이프라인 완주 — 상세 해소(장르 체인) → 분류(테스트 기본 mock — 결정적) → 승격. */
	private void drainPipeline() {
		detailEnricher.enrichDetails();
		genreEnricher.enrichGenres();
		draftPromoter.promoteReady(); // 승격 소비(ADR-12)
	}

	/** 벤더층에 적재되는 목록 스냅샷 어휘(응답 아이템 원문 verbatim — ADR-13). */
	private CatalogVendorItem listVendor(String externalId, String title) {
		return new CatalogVendorItem(externalId, title, null, null, "시립미술관", "전시", "서울", null, null, null,
				null, "전시", null, null, null, null, null, null, null, null);
	}

	private CatalogVendorItem detailVendor(String externalId, String price) {
		return new CatalogVendorItem(externalId, null, null, null, null, null, null, null, null, null, null,
				null, price, "<p>설명</p>", null, null, null, null, null, null);
	}

	private CatalogExhibitionData listData(String externalId, String title, CatalogVendorItem vendor) {
		LocalDate today = LocalDate.now();
		return new CatalogExhibitionData(externalId, title, "시립미술관", today.minusDays(1), today.plusDays(10),
				ExhibitionRegion.SEOUL, ExhibitionCategory.PAINTING, null, null, "기관", null, null, null, "전시",
				"서울", vendor);
	}

	private DetailFetch detailData(CatalogVendorItem vendor) {
		return new DetailFetch(new CatalogDetailData("무료", "설명", null, null, null, null, "서울시 종로구", "PLACE-1"),
				vendor);
	}

	private CultureListSnapshot listRow(String externalId) {
		return cultureListSnapshotRepository.findByExternalId(externalId).orElseThrow();
	}

	private CultureDetailSnapshot detailRow(String externalId) {
		return cultureDetailSnapshotRepository.findByExternalId(externalId).orElseThrow();
	}
}
