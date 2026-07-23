package modi.backend.ingestion.application.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestion.domain.audit.ExternalApi;
import modi.backend.ingestion.domain.audit.ExternalApiCallLog;
import modi.backend.ingestion.domain.audit.ExternalApiCallLogRepository;
import modi.backend.ingestion.domain.audit.ExternalApiOutcome;

/**
 * 외부 호출 감사 Recorder의 <b>삼킴 계약</b> 검증(순수 단위) — 이번 리팩토링이 REQUIRES_NEW를 infra에서
 * 이 Recorder로 올리면서 새로 만든 불변식이다: <b>부가 기록의 저장 실패가 비즈니스 흐름(수집·반영)을 깨면
 * 안 된다</b>. 이 계약이 회귀하면 감사 테이블 장애가 곧 수집 전면 장애가 된다.
 * (REQUIRES_NEW 전파 속성 자체는 트랜잭션 인프라 소관 — 커밋 게이트의 통합 테스트가 본다.)
 */
class ExternalApiCallLogRecorderTest {

	private final ExternalApiCallLogRepository repository = mock(ExternalApiCallLogRepository.class);
	private final ExternalApiCallLogRecorder recorder = new ExternalApiCallLogRecorder(repository);

	private static ExternalApiCallLog log() {
		return ExternalApiCallLog.of(ExternalApi.CULTURE_DETAIL, "EXT-1", ExternalApiOutcome.SUCCESS,
				LocalDateTime.now());
	}

	@Test
	@DisplayName("record — 저장소가 예외를 던져도 전파하지 않는다(감사 실패가 비즈니스 실패가 되지 않게 삼킨다)")
	void record_저장실패_삼킴() {
		given(repository.save(any())).willThrow(new RuntimeException("audit table down"));

		assertThatCode(() -> recorder.record(log())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("record — 정상 경로에선 저장소에 그대로 위임한다")
	void record_정상_위임() {
		ExternalApiCallLog callLog = log();

		recorder.record(callLog);

		verify(repository).save(callLog);
	}
}
