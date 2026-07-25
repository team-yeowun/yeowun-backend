package modi.backend.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import modi.backend.application.exhibition.contract.ExhibitionRegistrar;
import modi.backend.application.exhibition.contract.ExhibitionRegistration;
import modi.backend.application.exhibition.contract.PlaceRegistrar;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.culture.ExhibitionKoreaCultureService;
import modi.backend.ingestion.application.genre.ExhibitionAiGenreService;
import modi.backend.ingestion.application.audit.IngestionRunRecorder;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.application.outbox.OutboxPublisher;
import modi.backend.ingestion.application.place.ExhibitionPlaceService;
import modi.backend.ingestion.application.progress.ExhibitionAssembler;
import modi.backend.ingestion.application.progress.ExhibitionProgressService;
import modi.backend.ingestion.application.progress.SnapshotLedger;
import modi.backend.ingestion.domain.SyncTrigger;
import modi.backend.ingestion.domain.data.CatalogExhibitionData;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.progress.ExhibitionProgress;
import modi.backend.ingestion.domain.progress.ExhibitionProgressRepository;
import modi.backend.ingestion.domain.progress.ProgressStatus;
import modi.backend.ingestion.properties.OutboxProperties;

/**
 * <b>파이프라인 시나리오 검증</b>(설계 §1-3) — Testcontainers 없이 인메모리 리포 페이크 + 실제 조율 로직
 * (진행 상태 서비스·발행 컴포넌트·아웃박스 소비 메커니즘·오케스트레이터)으로 전 구간을 돌린다.
 * 외부 접점(문화포털·AI·구글·코어 등록)만 mock — 실 API 호출 0.
 *
 * <p>검증하는 핵심 계약:
 * <ul>
 *   <li>정상 완주 — 스테이징→상세→장르→승격이 이벤트로만 전진한다.</li>
 *   <li>부분 실패 시 어셈블은 <b>일어나지 않는다</b>(DRAFT_READY가 존재하지 않으므로 — 반쪽 어셈블 표현 불가).</li>
 *   <li>재시도 성공 시 게이트를 채운 그 반영이 DRAFT_READY를 발행해 승격이 이어진다.</li>
 *   <li>총 3회 시도 소진(D5) → FAILED_PERMANENT + 진행 상태 FAILED 가시화(장르 포함 — 무기한 특례 폐지).</li>
 *   <li>전시장 축 실패는 승격을 막지 않는다(비차단).</li>
 * </ul>
 */
class IngestionPipelineScenarioTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 1, 0);

	// 인메모리 페이크(시간 선별은 OutboxMessageTest가 검증하므로 여기선 미종료 전부를 도래로 취급)
	private InMemoryProgressRepository progressRepository;
	private InMemoryOutboxRepository outboxRepository;

	// 실제 조율 로직
	private ExhibitionProgressService progressService;
	private ExhibitionOutboxService outboxService;
	private ExhibitionIngestionOrchestrator orchestrator;

	// 외부 접점 mock
	private ExhibitionKoreaCultureService cultureService;
	private ExhibitionAiGenreService genreService;
	private ExhibitionPlaceService placeService;
	private ExhibitionAssembler assembler;
	private ExhibitionRegistrar registrar;
	private SnapshotLedger ledger;

	@BeforeEach
	void setUp() {
		progressRepository = new InMemoryProgressRepository();
		outboxRepository = new InMemoryOutboxRepository();
		cultureService = mock(ExhibitionKoreaCultureService.class);
		genreService = mock(ExhibitionAiGenreService.class);
		placeService = mock(ExhibitionPlaceService.class);
		assembler = mock(ExhibitionAssembler.class);
		registrar = mock(ExhibitionRegistrar.class);
		ledger = mock(SnapshotLedger.class);

		OutboxPublisher publisher = new OutboxPublisher(outboxRepository, mock(ApplicationEventPublisher.class));
		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		given(transactionTemplate.execute(any())).willAnswer(inv ->
				((TransactionCallback<?>)inv.getArgument(0)).doInTransaction(null));
		progressService = new ExhibitionProgressService(progressRepository, ledger, assembler, publisher,
				registrar, transactionTemplate);
		// D5 정책 그대로: 총 시도 3회(최초 1 + 재시도 2)
		outboxService = new ExhibitionOutboxService(outboxRepository, new OutboxProperties(3, 60L, 3600L, 50, null, null, null));
		given(genreService.consumeBatchSize()).willReturn(10);
		given(genreService.maxBatchesPerRun()).willReturn(1);
		orchestrator = new ExhibitionIngestionOrchestrator(cultureService, genreService, placeService,
				progressService, outboxService, new IngestionRunRecorder(run -> run));
	}

	private static CatalogExhibitionData item(String externalId, String place) {
		return new CatalogExhibitionData(externalId, "제목 " + externalId, place, null, null, null, null,
				null, null, null, null, null, null, null, null);
	}

	private void sync(CatalogExhibitionData... items) {
		given(cultureService.fetchPages(any())).willReturn(List.of(items));
		orchestrator.syncCatalog(SyncTrigger.MANUAL);
	}

	private void wireHappyDetailAndGenre(String externalId) {
		given(cultureService.fetchDetail(externalId)).willReturn(mock(CultureDetailPayload.class));
		given(cultureService.genreInputOf(externalId)).willReturn(Optional.of(
				new GenreClassification("제목", null, null, "장소", null, null)));
		given(genreService.classify(org.mockito.ArgumentMatchers.eq(externalId), any()))
				.willReturn(new GenreResult("회화", GenreProvider.GEMINI, "gemini-2.5-flash"));
	}

	private void wirePromotion(String externalId, long exhibitionId) {
		ExhibitionRegistration registration = new ExhibitionRegistration(externalId, "제목", "장소", null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				"회화", GenreProvider.GEMINI, null);
		given(assembler.assemble(externalId)).willReturn(registration);
		given(registrar.register(any(), any())).willReturn(new ExhibitionRegistrar.Registered(exhibitionId));
	}

	private void consumeAll() {
		orchestrator.consumeGenreClassification();
		orchestrator.consumeDetailFetch();
		orchestrator.consumePromotion();
		orchestrator.consumePlaceInitialization();
	}

	@Test
	@DisplayName("정상 완주 — 스테이징이 이벤트 2종을 심고, 소비만으로 상세→장르→승격이 완주한다")
	void happy_path_completes_via_events_only() {
		wireHappyDetailAndGenre("EXT-1");
		wirePromotion("EXT-1", 42L);
		given(placeService.resolvePlace(any())).willReturn(new PlaceRegistrar.Resolved(7L, "장소", false));
		given(cultureService.catalogDataOf("EXT-1")).willReturn(Optional.of(item("EXT-1", "장소")));

		sync(item("EXT-1", "장소"));
		assertThat(outboxRepository.statusOf(IngestionEventType.DRAFT_STAGED, "EXT-1"))
				.isEqualTo(OutboxMessageStatus.PENDING);
		assertThat(outboxRepository.statusOf(IngestionEventType.PLACE_STAGED, "장소"))
				.isEqualTo(OutboxMessageStatus.PENDING);

		consumeAll(); // 상세 → (장르 이벤트 생성) — 이후 소비에서 장르·승격 순차 소비
		consumeAll();
		consumeAll();

		ExhibitionProgress progress = progressRepository.findByExternalId("EXT-1").orElseThrow();
		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.COMPLETED);
		assertThat(progress.getPromotedExhibitionId()).isEqualTo(42L);
		then(registrar).should(times(1)).register(any(), any());
		assertThat(outboxRepository.statusOf(IngestionEventType.DRAFT_READY, "EXT-1"))
				.isEqualTo(OutboxMessageStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("부분 실패(장르 실패) — DRAFT_READY가 세상에 없으므로 어셈블·등록은 절대 불리지 않는다")
	void partial_failure_never_assembles() {
		wireHappyDetailAndGenre("EXT-1");
		willThrow(new RuntimeException("AI 장애")).given(genreService).classify(anyString(), any());

		sync(item("EXT-1", "장소"));
		consumeAll();
		consumeAll();

		assertThat(outboxRepository.find(IngestionEventType.DRAFT_READY, "EXT-1")).isEmpty();
		then(assembler).should(never()).assemble(anyString());
		then(registrar).should(never()).register(any(), any());
		assertThat(progressRepository.findByExternalId("EXT-1").orElseThrow().getStatus())
				.isEqualTo(ProgressStatus.ENRICHING); // 아직 재시도 여지가 있다 — FAILED 아님
	}

	@Test
	@DisplayName("재시도 성공 — 2회 실패 후 3회째 성공하면, 그 성공 반영이 DRAFT_READY를 쏘고 승격이 이어진다")
	void retry_then_success_assembles() {
		wireHappyDetailAndGenre("EXT-1");
		wirePromotion("EXT-1", 42L);
		given(genreService.classify(anyString(), any()))
				.willThrow(new RuntimeException("일시 장애 1"))
				.willThrow(new RuntimeException("일시 장애 2"))
				.willReturn(new GenreResult("회화", GenreProvider.OPENAI, "gpt")); // 폴백 성공 사례

		sync(item("EXT-1", "장소"));
		orchestrator.consumeDetailFetch();                 // 상세 해소 → DETAIL_FETCHED 발행
		orchestrator.consumeGenreClassification();         // 시도 1 — 실패(RETRYABLE)
		assertThat(outboxRepository.statusOf(IngestionEventType.DETAIL_FETCHED, "EXT-1"))
				.isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE);
		orchestrator.consumeGenreClassification();         // 시도 2 — 실패(RETRYABLE)
		orchestrator.consumeGenreClassification();         // 시도 3 — 성공 → 게이트 충족 → DRAFT_READY
		orchestrator.consumePromotion();                   // 승격 완주

		assertThat(progressRepository.findByExternalId("EXT-1").orElseThrow().getStatus())
				.isEqualTo(ProgressStatus.COMPLETED);
		then(registrar).should(times(1)).register(any(), any());
	}

	@Test
	@DisplayName("총 3회 소진(D5) — 장르가 FAILED_PERMANENT로 굳고 진행 상태가 FAILED로 가시화된다(무기한 특례 폐지)")
	void exhausted_attempts_visualize_failure() {
		wireHappyDetailAndGenre("EXT-1");
		willThrow(new RuntimeException("AI 장애")).given(genreService).classify(anyString(), any());

		sync(item("EXT-1", "장소"));
		orchestrator.consumeDetailFetch();
		orchestrator.consumeGenreClassification(); // 1
		orchestrator.consumeGenreClassification(); // 2
		orchestrator.consumeGenreClassification(); // 3 — 소진 → PERMANENT + 가시화 콜백

		assertThat(outboxRepository.statusOf(IngestionEventType.DETAIL_FETCHED, "EXT-1"))
				.isEqualTo(OutboxMessageStatus.FAILED_PERMANENT);
		ExhibitionProgress progress = progressRepository.findByExternalId("EXT-1").orElseThrow();
		assertThat(progress.getStatus()).isEqualTo(ProgressStatus.FAILED);
		assertThat(progress.getLastError()).contains("AI 장애");
		then(registrar).should(never()).register(any(), any());

		orchestrator.consumeGenreClassification(); // 소진 후 — 더는 시도하지 않는다(관리자 수동 재시도만)
		then(genreService).should(times(3)).classify(anyString(), any());
	}

	@Test
	@DisplayName("전시장 축 실패는 승격 비차단 — PLACE_STAGED가 영구히 굳어도 전시는 COMPLETED된다(영업시간만 빈다)")
	void place_axis_failure_does_not_block_promotion() {
		wireHappyDetailAndGenre("EXT-1");
		wirePromotion("EXT-1", 42L);
		given(cultureService.catalogDataOf("EXT-1")).willReturn(Optional.of(item("EXT-1", "장소")));
		willThrow(new RuntimeException("resolve 실패")).given(placeService).resolvePlace(any());

		sync(item("EXT-1", "장소"));
		for (int i = 0; i < 4; i++) {
			consumeAll();
		}

		assertThat(outboxRepository.statusOf(IngestionEventType.PLACE_STAGED, "장소"))
				.isEqualTo(OutboxMessageStatus.FAILED_PERMANENT);
		assertThat(progressRepository.findByExternalId("EXT-1").orElseThrow().getStatus())
				.isEqualTo(ProgressStatus.COMPLETED);
	}

	@Test
	@DisplayName("전시장 축 dedup — 같은 장소 전시 2건이어도 PLACE_STAGED는 1건, 신규면 구글 조회는 1번이다")
	void place_staged_deduplicated_per_place() {
		wireHappyDetailAndGenre("EXT-1");
		wireHappyDetailAndGenre("EXT-2");
		given(cultureService.catalogDataOf(anyString())).willReturn(Optional.of(item("EXT-1", "같은장소")));
		given(placeService.resolvePlace(any())).willReturn(new PlaceRegistrar.Resolved(7L, "같은장소", true));
		given(placeService.read(any())).willReturn(Optional.empty());

		sync(item("EXT-1", "같은장소"), item("EXT-2", "같은장소"));
		assertThat(outboxRepository.count(IngestionEventType.PLACE_STAGED)).isEqualTo(1);

		orchestrator.consumePlaceInitialization();
		then(placeService).should(times(1)).read(any());   // 장소당 1콜
		then(placeService).should(times(1)).applyVenueHours(any(), any(), any()); // NO_DATA도 반영(시각 남김)
		// 신규/기존 마크가 같은 장소의 진행 행 전부에 찍힌다.
		assertThat(progressRepository.findAllByPlaceKey("같은장소"))
				.allMatch(p -> p.getPlaceOutcome() != null);
	}

	// ── 인메모리 페이크 ────────────────────────────────────────────────────────────

	private static final class InMemoryProgressRepository implements ExhibitionProgressRepository {
		private final java.util.Map<String, ExhibitionProgress> rows = new java.util.LinkedHashMap<>();

		@Override
		public ExhibitionProgress save(ExhibitionProgress progress) {
			rows.put(progress.getExternalId(), progress);
			return progress;
		}

		@Override
		public Optional<ExhibitionProgress> findByExternalId(String externalId) {
			return Optional.ofNullable(rows.get(externalId));
		}

		@Override
		public List<ExhibitionProgress> findAllByPlaceKey(String placeKey) {
			return rows.values().stream().filter(p -> placeKey.equals(p.getPlaceKey())).toList();
		}
	}

	/** 미종료 메시지 전부를 도래로 취급한다 — 시간 선별(isDue)은 OutboxMessageTest가 검증한다. */
	private static final class InMemoryOutboxRepository implements OutboxMessageRepository {
		private final List<OutboxMessage> rows = new ArrayList<>();

		@Override
		public OutboxMessage save(OutboxMessage message) {
			if (!rows.contains(message)) {
				rows.add(message);
			}
			return message;
		}

		@Override
		public Optional<OutboxMessage> findByMessageTypeAndTargetKey(IngestionEventType type, String targetKey) {
			return rows.stream()
					.filter(m -> m.getMessageType() == type && m.getTargetKey().equals(targetKey))
					.findFirst();
		}

		@Override
		public List<OutboxMessage> findDue(IngestionEventType type, LocalDateTime now, int limit) {
			return rows.stream()
					.filter(m -> m.getMessageType() == type && m.getStatus().isPending())
					.sorted(Comparator.comparing(OutboxMessage::getTargetKey))
					.limit(limit)
					.toList();
		}

		@Override
		public long countByStatus(OutboxMessageStatus status) {
			return rows.stream().filter(m -> m.getStatus() == status).count();
		}

		@Override
		public int purgeSucceededBefore(LocalDateTime cutoff, int limit) {
			List<OutboxMessage> targets = rows.stream()
					.filter(m -> m.getStatus() == OutboxMessageStatus.SUCCEEDED)
					.filter(m -> m.getCompletedAt() != null && m.getCompletedAt().isBefore(cutoff))
					.limit(limit)
					.toList();
			rows.removeAll(targets);
			return targets.size();
		}

		OutboxMessageStatus statusOf(IngestionEventType type, String targetKey) {
			return find(type, targetKey).orElseThrow().getStatus();
		}

		Optional<OutboxMessage> find(IngestionEventType type, String targetKey) {
			return findByMessageTypeAndTargetKey(type, targetKey);
		}

		long count(IngestionEventType type) {
			return rows.stream().filter(m -> m.getMessageType() == type).count();
		}
	}
}
