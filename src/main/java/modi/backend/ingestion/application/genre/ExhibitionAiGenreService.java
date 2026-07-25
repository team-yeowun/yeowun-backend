package modi.backend.ingestion.application.genre;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import modi.backend.application.audit.ExternalApiCallLogRecorder;
import modi.backend.domain.audit.ApiCallSource;
import modi.backend.domain.audit.ExternalApi;
import modi.backend.domain.audit.ExternalApiCallLog;
import modi.backend.domain.audit.ExternalApiOutcome;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.properties.CatalogEnrichProperties;
import modi.backend.ingestion.properties.GeminiProperties;

/**
 * AI 장르 분류 축의 서비스 — <b>AI 호출(tx 밖)·AI 콜 감사(model 포함)</b>를 맡는다. 다음 스텝 지식은 없다 —
 * ① 판정·③ 반영(원장+마커)은 {@code ExhibitionProgressService}가, 소비 순서는 오케스트레이터가 안다.
 *
 * <p><b>draft당 개별 AI 호출</b>(사용자 확정 — 배치 분류 재도입 금지). 폴백 체인(1차 Gemini → 2차 OpenAI)은
 * 분류기({@code FailoverGenreClassifier}) 내부다 — 체인이 전부 실패하면 예외가 전파돼 이벤트가 실패 전이된다
 * (D5: 단일 정책, 총 3회 시도 후 FAILED_PERMANENT + 진행 상태 FAILED 가시화. 구 무기한 특례 폐지).
 *
 * <p><b>콜 감사</b>: 성공은 결과가 말하는 실제 공급자({@link GenreResult#provider()})로 남긴다 — 폴백으로 OpenAI가
 * 응답했으면 OPENAI 행이다(구 코드의 "대표값 GEMINI" 한계 해소). 실패는 어느 공급자가 최종인지 알 수 없어
 * 설정 기반 대표값으로 남긴다. mock은 외부 호출이 없으니 기록하지 않는다(유령 감사 금지).
 */
@Service
@RequiredArgsConstructor
public class ExhibitionAiGenreService {

	/** 장르 분류 전략(AI 체인/mock) — {@code app.exhibition.genre.classifier}로 선택된다(@Primary). */
	private final GenreClassifier genreClassifier;
	/** 외부 호출 감사(공용) — REQUIRES_NEW + 삼킴은 Recorder가 진다. */
	private final ExternalApiCallLogRecorder callLogRecorder;
	/** 1차 공급자 연결 설정 — 실패 감사 행의 요청 모델 값으로만 쓴다. */
	private final GeminiProperties geminiProperties;
	/** 장르 소비 유량 정책(배치 크기·실행당 배치 수) — 이 축의 정책이라 여기가 소유한다. */
	private final CatalogEnrichProperties enrichProperties;

	/** 어떤 분류기가 선택돼 있나 — 실패 감사 행의 api 매핑 재료(mock이면 기록 생략). */
	@Value("${app.exhibition.genre.classifier:mock}")
	private String classifier;

	/** 소비 한 배치의 크기 — 배치당 AI 최대 콜 수와 같다(이벤트당 1콜). */
	public int consumeBatchSize() {
		return enrichProperties.genreBatchSize();
	}

	/** 실행당 최대 배치 수 — {@code consumeBatchSize() × maxBatchesPerRun()}이 실행당 처리 상한(유량 제어)이다. */
	public int maxBatchesPerRun() {
		return enrichProperties.genreMaxBatchesPerRun();
	}

	/**
	 * 장르 분류 <b>단일 시도</b>(tx 밖) — 요청 조립 → AI 호출(체인 내부 폴백 포함) → 콜 감사.
	 * 실패도 감사에 남기고 그대로 전파한다(이벤트 수명주기는 호출부가 잇는다).
	 *
	 * @param externalId 감사 requestKey(대상 전시 원천키)
	 */
	public GenreResult classify(String externalId, GenreClassification subject) {
		LocalDateTime calledAt = LocalDateTime.now();
		GenreResult result;
		try {
			result = genreClassifier.classify(genreRequest(subject));
		} catch (RuntimeException e) {
			recordFailure(externalId, calledAt);
			throw e;
		}
		recordSuccess(externalId, result, calledAt);
		return result;
	}

	/** 이 유스케이스가 분류기에게 무엇을 시킬지 — 표준 지시 + 마스터 전체 허용. 요청 조립은 서비스의 결정이다. */
	private GenreClassificationRequest genreRequest(GenreClassification subject) {
		return new GenreClassificationRequest(
				GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText());
	}

	/** 성공 감사 — 결과의 실제 공급자·모델로 남긴다(폴백이면 2차 공급자 행). mock/레거시 값은 생략. */
	private void recordSuccess(String externalId, GenreResult result, LocalDateTime calledAt) {
		ExternalApi api = apiOf(result.provider());
		if (api == null) {
			return; // mock 등 — 외부 호출이 일어나지 않았다.
		}
		callLogRecorder.record(ExternalApiCallLog.ai(ApiCallSource.INGESTION, api, result.model(),
				externalId, ExternalApiOutcome.SUCCESS, calledAt));
	}

	/** 실패 감사 — 최종 공급자를 알 수 없어 설정 기반 대표값(gemini 체인=GEMINI, claude=CLAUDE, mock=생략). */
	private void recordFailure(String externalId, LocalDateTime calledAt) {
		if ("gemini".equalsIgnoreCase(classifier)) {
			callLogRecorder.record(ExternalApiCallLog.ai(ApiCallSource.INGESTION, ExternalApi.GEMINI,
					geminiProperties.model(), externalId, ExternalApiOutcome.FAILED, calledAt));
			return;
		}
		if ("claude".equalsIgnoreCase(classifier)) {
			callLogRecorder.record(ExternalApiCallLog.ai(ApiCallSource.INGESTION, ExternalApi.CLAUDE, null,
					externalId, ExternalApiOutcome.FAILED, calledAt));
		}
		// mock — 외부 호출이 일어나지 않았다. 부르지 않은 호출은 감사에 남기지 않는다.
	}

	private static ExternalApi apiOf(GenreProvider provider) {
		return switch (provider) {
			case GEMINI -> ExternalApi.GEMINI;
			case OPENAI -> ExternalApi.OPENAI;
			case CLAUDE -> ExternalApi.CLAUDE;
			default -> null; // MOCK·RANDOM·USER — 외부 호출 아님
		};
	}
}
