package modi.backend.ingestion.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestion.application.outbox.ExhibitionOutboxService;
import modi.backend.ingestion.application.outbox.OutboxPublisher;
import modi.backend.ingestion.infra.audit.IngestionRunJpaRepository;
import modi.backend.ingestion.infra.outbox.OutboxMessageJpaRepository;
import modi.backend.ingestion.infra.progress.ExhibitionProgressJpaRepository;
import modi.backend.ingestion.properties.OutboxProperties;

/** 관리자 파사드 단위 — 주간 정리(§9)의 루프 계약: 소량 배치 반복, 배치 미만이 나오면 종료, 보존 기간 컷오프. */
class IngestionAdminFacadeTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 3, 0);

	@Test
	@DisplayName("purgeSucceeded — 배치 크기만큼 나오는 동안 반복하고(500+500+120), 컷오프는 보존 기간(7일) 이전이다")
	void purge_loops_in_batches() {
		ExhibitionOutboxService outboxService = mock(ExhibitionOutboxService.class);
		given(outboxService.purgeSucceededBatch(any())).willReturn(500, 500, 120);
		IngestionAdminFacade facade = new IngestionAdminFacade(
				mock(ExhibitionProgressJpaRepository.class), mock(OutboxMessageJpaRepository.class),
				mock(IngestionRunJpaRepository.class), mock(OutboxPublisher.class),
				outboxService, new OutboxProperties(3, 60L, 3600L, 50, null, 7, 500));

		IngestionAdminResult.Purged purged = facade.purgeSucceeded(NOW);

		assertThat(purged.deleted()).isEqualTo(1120);
		assertThat(purged.cutoff()).isEqualTo(NOW.minusDays(7));
		then(outboxService).should(times(3)).purgeSucceededBatch(NOW.minusDays(7));
	}

	@Test
	@DisplayName("purgeSucceeded — 지울 게 없으면 1회 시도 후 0으로 끝난다(빈 주간도 안전)")
	void purge_empty_week() {
		ExhibitionOutboxService outboxService = mock(ExhibitionOutboxService.class);
		given(outboxService.purgeSucceededBatch(any())).willReturn(0);
		IngestionAdminFacade facade = new IngestionAdminFacade(
				mock(ExhibitionProgressJpaRepository.class), mock(OutboxMessageJpaRepository.class),
				mock(IngestionRunJpaRepository.class), mock(OutboxPublisher.class),
				outboxService, new OutboxProperties(3, 60L, 3600L, 50, null, 7, 500));

		assertThat(facade.purgeSucceeded(NOW).deleted()).isZero();
		then(outboxService).should(times(1)).purgeSucceededBatch(any());
	}
}
