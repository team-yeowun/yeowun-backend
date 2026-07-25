package modi.backend.ingestion.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestion.domain.SyncTrigger;

/** 런 감사(슬림 스키마 — 설계 §5-5) 순수 단위: trigger·시각·collected·inserted만 남았다. */
class IngestionRunTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 1, 0);

	@Test
	@DisplayName("started → fetched → recordStaged → finished — 슬림 집계 한 사이클")
	void slim_lifecycle() {
		IngestionRun run = IngestionRun.started(SyncTrigger.SCHEDULE, NOW);
		assertThat(run.getTriggerType()).isEqualTo(SyncTrigger.SCHEDULE);
		assertThat(run.hasActivity()).isFalse();

		run.fetched(25);
		run.recordStaged();
		run.recordStaged();
		run.finished(NOW.plusMinutes(1));

		assertThat(run.getCollected()).isEqualTo(25);
		assertThat(run.getInserted()).isEqualTo(2);
		assertThat(run.getFinishedAt()).isEqualTo(NOW.plusMinutes(1));
		assertThat(run.hasActivity()).isTrue();
	}

	@Test
	@DisplayName("hasActivity — 수집 0·신규 0이면 조용히 지나간다(요약 로그 발화 조건)")
	void no_activity_when_empty() {
		IngestionRun run = IngestionRun.started(SyncTrigger.MANUAL, NOW);
		run.fetched(0);
		assertThat(run.hasActivity()).isFalse();
	}
}
