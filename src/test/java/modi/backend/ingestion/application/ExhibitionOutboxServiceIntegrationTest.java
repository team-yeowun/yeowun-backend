package modi.backend.ingestion.application;

import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.ingestion.domain.outbox.OutboxFailureType;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.infra.exhibition.hours.PlaceHoursJpaRepository;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;

/**
 * 통합 작업큐 파사드·저장소 통합 검증(@SpringBootTest + Testcontainers-MySQL) — enqueue 멱등(UK), 선별 쿼리(findDue),
 * 낙관락 충돌 skip, 이벤트 구동 영업시간 재검증 가드(최소 간격·기존 장소만·재활성화). 이 경로들은 JPA @Version·UK·
 * 인덱스가 실제로 걸려야 검증되므로 순수 단위가 아닌 통합으로 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionOutboxServiceIntegrationTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionOutboxService exhibitionOutboxService;

	@Autowired
	OutboxMessageRepository outboxMessageRepository;

	@Autowired
	PlaceHoursJpaRepository placeHoursRepository;

	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	private String nextKey(String prefix) {
		return prefix + "-" + SEQ.getAndIncrement();
	}

	/**
	 * placeKey(=정규화 이름) 전시장 + 그 장소의 정준 영업시간(syncedAt)을 만든다. HOURS_REFRESH target_key는
	 * exhibition_place.place_key라, enqueueHoursRefresh가 이 전시장을 해소해 정준행을 본다(가드 판정).
	 */
	private void seedPlaceWithHours(String placeKey, LocalDateTime syncedAt) {
		Long placeId = exhibitionPlaceRepository.save(
				ExhibitionPlace.createFromList(placeKey, null, null, null, null)).getId();
		placeHoursRepository.save(PlaceHours.first(placeId, "매일 10:00~18:00",
				PlaceHoursStatus.SUCCEEDED, PlaceHoursVendor.GOOGLE, syncedAt));
	}

	@Test
	@DisplayName("enqueue 멱등 — 같은 (종류, 대상)을 두 번 넣어도 UK로 1건만 남는다")
	void enqueue_멱등() {
		String target = nextKey("EXT");
		LocalDateTime now = LocalDateTime.now();

		exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, target, now);
		exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, target, now.plusMinutes(5));

		List<OutboxMessage> due = exhibitionOutboxService.findDue(IngestionEventType.DRAFT_STAGED, 100, now.plusHours(1));
		assertThat(due.stream().filter(j -> j.getTargetKey().equals(target))).hasSize(1);
	}

	@Test
	@DisplayName("findDue 선별 — 미종료이고 도래한 작업만, 도래 순으로 나온다(종료·미도래는 제외)")
	void findDue_선별() {
		LocalDateTime now = LocalDateTime.now();
		String dueKey = nextKey("EXT");
		String futureKey = nextKey("EXT");
		String doneKey = nextKey("EXT");

		exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, dueKey, now); // PENDING·now → 도래
		// 미도래: 실패로 next_attempt_at을 미래로 민다.
		exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, futureKey, now);
		OutboxMessage future = outboxMessageRepository
				.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, futureKey).orElseThrow();
		exhibitionOutboxService.markFailed(future, OutboxFailureType.RETRYABLE, "e", now);
		// 종료: 성공 처리.
		exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, doneKey, now);
		OutboxMessage done = outboxMessageRepository
				.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, doneKey).orElseThrow();
		exhibitionOutboxService.markSucceeded(done, now);

		List<String> due = exhibitionOutboxService.findDue(IngestionEventType.DRAFT_STAGED, 100, now).stream()
				.map(OutboxMessage::getTargetKey).toList();

		assertThat(due).contains(dueKey).doesNotContain(futureKey, doneKey);
	}

	@Test
	@DisplayName("낙관락 충돌 — 같은 작업을 둘이 집어 둘 다 성공 전이하면 뒤쪽은 예외로 밀린다(drain이 이 예외를 skip으로 흡수한다)")
	void 낙관락_충돌_skip() {
		String target = nextKey("EXT");
		LocalDateTime now = LocalDateTime.now();
		exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, target, now);

		// 두 워커가 각자 집은 두 detached 사본(같은 version).
		OutboxMessage worker1 = outboxMessageRepository
				.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, target).orElseThrow();
		OutboxMessage worker2 = outboxMessageRepository
				.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, target).orElseThrow();

		exhibitionOutboxService.markSucceeded(worker1, now); // 이긴다

		// JPA @Version이 실제로 걸려 뒤쪽 저장이 밀리는지 — drain의 낙관락 skip 흡수가 기대는 메커니즘이다.
		assertThatThrownBy(() -> exhibitionOutboxService.markSucceeded(worker2, now))
				.isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, target)
				.orElseThrow().getStatus()).isEqualTo(OutboxMessageStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("markFailed 정책 분기 — CLASSIFY_GENRE는 시도 소진 없이 RETRYABLE(무기한), FETCH_DETAIL은 소진 시 PERMANENT 승격")
	void markFailed_장르_무기한정책() {
		String genreKey = nextKey("EXT");
		String detailKey = nextKey("EXT");
		LocalDateTime now = LocalDateTime.now();
		exhibitionOutboxService.enqueue(IngestionEventType.DETAIL_FETCHED, genreKey, now);
		exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, detailKey, now);

		// 기본 maxAttempts(5)를 넘겨 실패를 반복한다 — 이 분기가 회귀하면(기본 정책 사용) 장르도 PERMANENT로 굳어
		// draft가 영구 승격 불가가 된다(ADR-11의 핵심 요구).
		for (int i = 0; i < 7; i++) {
			OutboxMessage genreMessage = outboxMessageRepository
					.findByMessageTypeAndTargetKey(IngestionEventType.DETAIL_FETCHED, genreKey).orElseThrow();
			exhibitionOutboxService.markFailed(genreMessage, OutboxFailureType.RETRYABLE, "전 공급자 실패", now);
			OutboxMessage detailMessage = outboxMessageRepository
					.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, detailKey).orElseThrow();
			if (!detailMessage.isTerminal()) {
				exhibitionOutboxService.markFailed(detailMessage, OutboxFailureType.RETRYABLE, "timeout", now);
			}
		}

		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(IngestionEventType.DETAIL_FETCHED, genreKey)
				.orElseThrow().getStatus()).isEqualTo(OutboxMessageStatus.FAILED_RETRYABLE); // 소진 승격 없음 — 회복 대기
		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, detailKey)
				.orElseThrow().getStatus()).isEqualTo(OutboxMessageStatus.FAILED_PERMANENT); // 기본 정책은 소진 승격
	}

	@Test
	@DisplayName("HOURS_REFRESH 가드 — place_hours가 없으면(기존 장소 아님) enqueue하지 않는다")
	void 재검증_기존장소만() {
		String placeKey = nextKey("PLACE");
		LocalDateTime now = LocalDateTime.now();

		exhibitionOutboxService.enqueueHoursRefresh(placeKey, now);

		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(IngestionEventType.PLACE_HOURS_STALE, placeKey))
				.isEmpty();
	}

	@Test
	@DisplayName("HOURS_REFRESH 가드 — synced_at이 최소 간격 이내면 skip, 오래됐으면 enqueue한다")
	void 재검증_최소간격() {
		LocalDateTime now = LocalDateTime.now();
		String recentPlace = nextKey("PLACE");
		String stalePlace = nextKey("PLACE");
		// 최근 확인(1일 전) — 기본 최소 간격 30일 이내라 skip 대상.
		seedPlaceWithHours(recentPlace, now.minusDays(1));
		// 오래됨(60일 전) — 재검증 대상.
		seedPlaceWithHours(stalePlace, now.minusDays(60));

		exhibitionOutboxService.enqueueHoursRefresh(recentPlace, now);
		exhibitionOutboxService.enqueueHoursRefresh(stalePlace, now);

		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(IngestionEventType.PLACE_HOURS_STALE, recentPlace))
				.isEmpty(); // 최소 간격 이내 → skip
		assertThat(outboxMessageRepository.findByMessageTypeAndTargetKey(IngestionEventType.PLACE_HOURS_STALE, stalePlace))
				.isPresent(); // 오래됨 → enqueue
	}

	@Test
	@DisplayName("HOURS_REFRESH 가드 — 종료된 이전 재검증 작업은 되살린다(재검증은 반복 이벤트)")
	void 재검증_종료작업_재활성화() {
		LocalDateTime now = LocalDateTime.now();
		String placeKey = nextKey("PLACE");
		seedPlaceWithHours(placeKey, now.minusDays(60));
		exhibitionOutboxService.enqueueHoursRefresh(placeKey, now);
		OutboxMessage job = outboxMessageRepository
				.findByMessageTypeAndTargetKey(IngestionEventType.PLACE_HOURS_STALE, placeKey).orElseThrow();
		exhibitionOutboxService.markSucceeded(job, now); // 종료

		exhibitionOutboxService.enqueueHoursRefresh(placeKey, now.plusDays(40)); // 다시 오래됨 → 되살아나야 한다

		OutboxMessage reactivated = outboxMessageRepository
				.findByMessageTypeAndTargetKey(IngestionEventType.PLACE_HOURS_STALE, placeKey).orElseThrow();
		assertThat(reactivated.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);
		assertThat(reactivated.getAttemptCount()).isZero();
	}
}
