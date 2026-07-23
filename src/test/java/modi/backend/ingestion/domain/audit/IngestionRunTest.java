package modi.backend.ingestion.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestion.application.draft.ExhibitionDraftService.StageOutcome;
import modi.backend.ingestion.domain.SyncTrigger;

/**
 * 수집 런 집계의 <b>카운팅 행위</b> 단위 검증(순수) — 집계 규칙이 파사드 지역변수에서 엔티티 행위로 이동한
 * 계약을 핀한다: StageOutcome→컬럼 매핑(STAGED→inserted·REFRESHED→completed·SKIPPED→무집계),
 * 기간스킵/실패연기 누적.
 */
class IngestionRunTest {

	private IngestionRun run() {
		return IngestionRun.started(SyncTrigger.MANUAL, LocalDateTime.now());
	}

	@Test
	@DisplayName("record(StageOutcome) — STAGED는 inserted, REFRESHED는 completed에 누적되고 SKIPPED는 어디에도 잡히지 않는다")
	void 스테이징결과_매핑() {
		IngestionRun run = run();

		run.record(StageOutcome.STAGED);
		run.record(StageOutcome.STAGED);
		run.record(StageOutcome.REFRESHED);
		run.record(StageOutcome.SKIPPED);

		assertThat(run.getInserted()).isEqualTo(2);
		assertThat(run.getCompleted()).isEqualTo(1);
		assertThat(run.getSkipped()).isZero(); // SKIPPED(이미 완성/종료)는 기간스킵(skipped 컬럼)과 다르다 — 무집계
		assertThat(run.getInserted()).isEqualTo(2); // 신규 스테이징 수(inserted)
	}

	@Test
	@DisplayName("기간스킵·실패연기 — 각자 컬럼에 누적되고 hasActivity가 참이 된다(전부 0이면 거짓)")
	void 스킵과_연기_누적() {
		IngestionRun run = run();
		assertThat(run.hasActivity()).isFalse(); // 요약 로그 발화 조건 — 아무 일 없으면 조용히

		run.recordPeriodSkipped();
		run.recordDeferred();

		assertThat(run.getSkipped()).isEqualTo(1);
		assertThat(run.getDeferred()).isEqualTo(1);
		assertThat(run.getInserted()).isZero();
		assertThat(run.hasActivity()).isTrue();
	}

	@Test
	@DisplayName("finished — 종료 시각만 기록한다(집계는 루프 중 이미 누적)")
	void 종료시각_기록() {
		IngestionRun run = run();
		LocalDateTime end = LocalDateTime.now();

		run.finished(end);

		assertThat(run.getFinishedAt()).isEqualTo(end);
	}
}
