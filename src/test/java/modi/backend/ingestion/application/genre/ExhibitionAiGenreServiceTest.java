package modi.backend.ingestion.application.genre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.properties.CatalogEnrichProperties;
import modi.backend.ingestion.properties.GeminiProperties;

/**
 * 장르 축 서비스 단위 — 개별 호출·콜 감사(성공=실제 공급자, 실패=설정 대표값, mock=생략)를 못박는다.
 * 폴백 체인(Gemini→OpenAI)은 분류기 내부라 여기선 "성공 결과의 공급자가 감사에 그대로 남는가"만 본다.
 */
class ExhibitionAiGenreServiceTest {

	private GenreClassifier classifier;
	private ExternalApiCallLogRecorder recorder;
	private ExhibitionAiGenreService service;

	@BeforeEach
	void setUp() {
		classifier = mock(GenreClassifier.class);
		recorder = mock(ExternalApiCallLogRecorder.class);
		service = new ExhibitionAiGenreService(classifier, recorder,
				new GeminiProperties(null, null, "gemini-2.5-flash", null),
				new CatalogEnrichProperties(20, 3));
		ReflectionTestUtils.setField(service, "classifier", "gemini");
	}

	private static GenreClassification subject() {
		return new GenreClassification("제목", null, null, "장소", null, null);
	}

	@Test
	@DisplayName("성공 감사 — 결과의 실제 공급자로 남는다(폴백으로 OpenAI가 응답했으면 OPENAI 행, source=INGESTION)")
	void success_logged_with_actual_provider() {
		given(classifier.classify(any())).willReturn(new GenreResult("회화", GenreProvider.OPENAI, "gpt-x"));

		service.classify("EXT-1", subject());

		ArgumentCaptor<ExternalApiCallLog> captor = ArgumentCaptor.forClass(ExternalApiCallLog.class);
		then(recorder).should().record(captor.capture());
		assertThat(captor.getValue().getApi()).isEqualTo(ExternalApi.OPENAI);
		assertThat(captor.getValue().getSource()).isEqualTo(ApiCallSource.INGESTION);
		assertThat(captor.getValue().getOutcome()).isEqualTo(ExternalApiOutcome.SUCCESS);
		assertThat(captor.getValue().getRequestKey()).isEqualTo("EXT-1");
	}

	@Test
	@DisplayName("실패 감사 — 예외는 그대로 전파하고(수명주기는 아웃박스 몫) 설정 대표값(GEMINI)으로 FAILED 행을 남긴다")
	void failure_logged_and_propagated() {
		willThrow(new RuntimeException("체인 전부 실패")).given(classifier).classify(any());

		assertThatThrownBy(() -> service.classify("EXT-1", subject())).isInstanceOf(RuntimeException.class);

		ArgumentCaptor<ExternalApiCallLog> captor = ArgumentCaptor.forClass(ExternalApiCallLog.class);
		then(recorder).should().record(captor.capture());
		assertThat(captor.getValue().getApi()).isEqualTo(ExternalApi.GEMINI);
		assertThat(captor.getValue().getOutcome()).isEqualTo(ExternalApiOutcome.FAILED);
	}

	@Test
	@DisplayName("mock 분류기 — 외부 호출이 없으니 감사도 남기지 않는다(유령 감사 금지)")
	void mock_not_logged() {
		ReflectionTestUtils.setField(service, "classifier", "mock");
		given(classifier.classify(any())).willReturn(GenreResult.mock("회화"));

		service.classify("EXT-1", subject());
		willThrow(new RuntimeException("mock 실패")).given(classifier).classify(any());
		assertThatThrownBy(() -> service.classify("EXT-2", subject())).isInstanceOf(RuntimeException.class);

		then(recorder).should(never()).record(any());
	}
}
