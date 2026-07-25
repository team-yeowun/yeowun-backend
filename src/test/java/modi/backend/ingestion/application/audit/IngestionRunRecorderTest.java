package modi.backend.ingestion.application.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestion.domain.SyncTrigger;
import modi.backend.ingestion.domain.audit.IngestionRun;
import modi.backend.ingestion.domain.audit.IngestionRunRepository;

/**
 * 수집 런 감사 Recorder의 <b>best-effort 삼킴 계약</b> 검증(순수 단위) — 런 기록 실패가 동기화 결과
 * (staged 반환·후속 흐름)를 깨면 안 된다. 콜 감사 Recorder와 달리 REQUIRES_NEW가 아닌 것은 의도다
 * (루프 종료 후 트랜잭션 밖 1회 호출 — 분리할 트랜잭션이 없다).
 */
class IngestionRunRecorderTest {

	private final IngestionRunRepository repository = mock(IngestionRunRepository.class);
	private final IngestionRunRecorder recorder = new IngestionRunRecorder(repository);

	@Test
	@DisplayName("record — 저장소가 예외를 던져도 전파하지 않는다(런 감사 실패가 동기화를 깨지 않게 삼킨다)")
	void record_저장실패_삼킴() {
		given(repository.save(any())).willThrow(new RuntimeException("run table down"));

		assertThatCode(() -> recorder.record(IngestionRun.started(SyncTrigger.MANUAL, LocalDateTime.now())))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("record — 정상 경로에선 종료 시각을 마감하고 저장소에 위임한다(집계는 run이 이미 누적)")
	void record_정상_위임() {
		IngestionRun run = IngestionRun.started(SyncTrigger.MANUAL, LocalDateTime.now());

		recorder.record(run);

		assertThat(run.getFinishedAt()).isNotNull();
		verify(repository).save(run);
	}
}
