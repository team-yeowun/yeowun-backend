package modi.backend.ingestion.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;
import modi.backend.ingestion.domain.outbox.OutboxMessageStatus;
import modi.backend.ingestion.domain.outbox.IngestionEventType;

/**
 * 릴레이 이벤트 글루 통합 검증 — enqueue 트랜잭션 커밋 직후({@code AFTER_COMMIT}) 릴레이가 비동기로 드레인해
 * 폴링 주기를 기다리지 않고 메시지가 처리되는지 확인한다(ADR-10 "이벤트=글루"). 폴링은 1시간으로 사실상 꺼두므로
 * 이 테스트에서 상태 전이가 일어났다면 그 경로는 이벤트 드레인뿐이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.exhibition.enrich.scheduling-enabled=true",
		"app.exhibition.outbox.poll-interval-ms=3600000" // durable 엔진(폴링)은 꺼두고 글루(이벤트)만 남긴다
})
class ExhibitionOutboxRelayIntegrationTest {

	@Autowired
	ExhibitionOutboxService exhibitionOutboxService;

	@Autowired
	OutboxMessageRepository outboxMessageRepository;

	@Test
	@DisplayName("AFTER_COMMIT 드레인 — enqueue 커밋 직후 릴레이가 도래 메시지를 폴링 주기 없이 처리한다")
	void 커밋직후_이벤트드레인() throws InterruptedException {
		String target = "RELAY-" + System.nanoTime();

		// 대상 draft가 없는 DRAFT_STAGED — 드레인되면 "할 일 없음"으로 SUCCEEDED 마감된다(멱등 소비).
		// 이 전이가 곧 "이벤트 드레인이 돌았다"의 관측 가능한 증거다(외부 호출 없음).
		// ※ 레거시 전시 폴백(ExhibitionBackfill) 삭제 전에는 같은 입력이 "대상 미존재"로 RETRYABLE이었다 —
		//   draft 단일 경로가 되면서 성공 마감으로 바뀌었다(나중에 draft가 생기면 재sync 안전망이 이벤트를 부활시킨다).
		exhibitionOutboxService.enqueue(IngestionEventType.DRAFT_STAGED, target, LocalDateTime.now());

		OutboxMessageStatus observed = null;
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			observed = outboxMessageRepository
					.findByMessageTypeAndTargetKey(IngestionEventType.DRAFT_STAGED, target)
					.orElseThrow().getStatus();
			if (observed == OutboxMessageStatus.SUCCEEDED) {
				break;
			}
			Thread.sleep(200);
		}

		assertThat(observed).isEqualTo(OutboxMessageStatus.SUCCEEDED);
	}
}
