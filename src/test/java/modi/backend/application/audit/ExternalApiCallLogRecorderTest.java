package modi.backend.application.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiCallLogRepository;
import modi.backend.domain.audit.ExternalApiOutcome;

/** 공용 콜 감사 레코더(코어 이동 — 설계 D3) 단위: 저장 실패는 삼킨다 — 부가 기록이 본 기능을 멈추지 않는다. */
class ExternalApiCallLogRecorderTest {

	@Test
	@DisplayName("record — 저장 실패를 삼킨다(REQUIRES_NEW 경계와 별개로 예외 오염 차단)")
	void swallow_save_failure() {
		ExternalApiCallLogRepository repository = mock(ExternalApiCallLogRepository.class);
		willThrow(new RuntimeException("DB down")).given(repository).save(any());
		ExternalApiCallLogRecorder recorder = new ExternalApiCallLogRecorder(repository);

		assertThatCode(() -> recorder.record(ExternalApiCallLog.of(ApiCallSource.INGESTION,
				ExternalApi.CULTURE_LIST, "page=1", ExternalApiOutcome.SUCCESS, LocalDateTime.now())))
				.doesNotThrowAnyException();
	}
}
