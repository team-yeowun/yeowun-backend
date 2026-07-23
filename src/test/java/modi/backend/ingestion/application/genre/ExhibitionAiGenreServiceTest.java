package modi.backend.ingestion.application.genre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.audit.ExternalApiCallLogRecorder;
import modi.backend.ingestion.domain.audit.ExternalApi;
import modi.backend.ingestion.domain.audit.ExternalApiCallLog;
import modi.backend.ingestion.domain.audit.ExternalApiOutcome;
import modi.backend.ingestion.properties.CatalogEnrichProperties;
import modi.backend.ingestion.properties.GeminiProperties;

/**
 * AI 장르 분류 서비스의 <b>AI 콜 로그(신설) 매핑 계약</b> 검증(순수 단위) — 재설계 전엔 AI 호출 감사가
 * 통째로 누락돼 있었다(행위 변경 D2). 설정값({@code app.exhibition.genre.classifier}) 기반 매핑을 못박는다:
 * <ul>
 *   <li>{@code gemini} → api=GEMINI + 요청 모델. <b>대표값 한계(구현 주석 명시)</b>: 실제 폴백 체인은
 *       1차 Gemini → 2차 OpenAI라, 2차로 전환돼 성공한 호출도 GEMINI 한 행으로 남는다 — 이 테스트는
 *       현 구현 계약(대표값 방식)을 기준으로 한다.</li>
 *   <li>{@code claude} → api=CLAUDE(전환 대비 자리), model 없음.</li>
 *   <li>{@code mock} → <b>기록 생략</b>(외부 호출이 없으니 유령 감사 행을 남기지 않는다).</li>
 *   <li>실패도 한 행(FAILED) — 시도 = 한도 소모라 실패도 사실로 남긴다. 예외는 그대로 전파(장르는 호출부가
 *       RETRYABLE로 잇는다).</li>
 * </ul>
 */
class ExhibitionAiGenreServiceTest {

	private GenreClassifier classifier;
	private ExternalApiCallLogRecorder recorder;
	private ExhibitionAiGenreService service;

	private final GenreClassification subject = new GenreClassification("전시", null, null, null, null, null);

	@BeforeEach
	void setUp() {
		classifier = mock(GenreClassifier.class);
		recorder = mock(ExternalApiCallLogRecorder.class);
		service = new ExhibitionAiGenreService(classifier, recorder,
				new GeminiProperties(null, null, "gemini-2.5-flash", null), new CatalogEnrichProperties(2, 2));
	}

	/** {@code @Value} 필드라 컨텍스트 없는 단위에선 직접 주입한다(생성자 인자가 아님 — 기존 Relay 패턴과 동일). */
	private void classifierSetting(String value) {
		ReflectionTestUtils.setField(service, "classifier", value);
	}

	private ExternalApiCallLog recordedLog() {
		ArgumentCaptor<ExternalApiCallLog> captor = ArgumentCaptor.forClass(ExternalApiCallLog.class);
		verify(recorder).record(captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("gemini 설정 — 분류 성공이 api=GEMINI·요청 모델·requestKey=external_id·SUCCESS 한 행으로 남는다")
	void classify_gemini_성공로그() {
		classifierSetting("gemini");
		given(classifier.classify(any())).willReturn(GenreResult.ai("사진", GenreProvider.GEMINI, "gemini-2.5-flash"));

		GenreResult result = service.classify("EXT-1", subject);

		assertThat(result.genreKeyword()).isEqualTo("사진");
		ExternalApiCallLog log = recordedLog();
		assertThat(log.getApi()).isEqualTo(ExternalApi.GEMINI);
		assertThat(log.getModel()).isEqualTo("gemini-2.5-flash"); // 요청 모델(설정값) — 실서빙 모델은 정준층 몫
		assertThat(log.getRequestKey()).isEqualTo("EXT-1");
		assertThat(log.getOutcome()).isEqualTo(ExternalApiOutcome.SUCCESS);
	}

	@Test
	@DisplayName("gemini 설정 — 분류 실패도 FAILED 한 행으로 남기고 예외는 그대로 전파한다(시도 = 한도 소모)")
	void classify_gemini_실패로그_전파() {
		classifierSetting("gemini");
		given(classifier.classify(any())).willThrow(new GenreClassificationException("전 공급자 실패"));

		assertThatThrownBy(() -> service.classify("EXT-1", subject))
				.isInstanceOf(GenreClassificationException.class);

		ExternalApiCallLog log = recordedLog();
		assertThat(log.getApi()).isEqualTo(ExternalApi.GEMINI);
		assertThat(log.getOutcome()).isEqualTo(ExternalApiOutcome.FAILED);
	}

	@Test
	@DisplayName("claude 설정 — api=CLAUDE(전환 대비 자리)·model 없음으로 남는다")
	void classify_claude_매핑() {
		classifierSetting("claude");
		given(classifier.classify(any())).willReturn(GenreResult.ai("공예", GenreProvider.CLAUDE, "claude-haiku"));

		service.classify("EXT-2", subject);

		ExternalApiCallLog log = recordedLog();
		assertThat(log.getApi()).isEqualTo(ExternalApi.CLAUDE);
		assertThat(log.getModel()).isNull();
		assertThat(log.getRequestKey()).isEqualTo("EXT-2");
	}

	@Test
	@DisplayName("mock 설정(기본) — 외부 호출이 없으니 감사 행을 남기지 않는다(유령 감사 행 차단)")
	void classify_mock_기록생략() {
		classifierSetting("mock");
		given(classifier.classify(any())).willReturn(GenreResult.ai("사진", GenreProvider.MOCK, null));

		service.classify("EXT-3", subject);

		verify(recorder, never()).record(any());
	}
}
