package modi.backend.ingestion.application.genre;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.application.audit.ExternalApiCallLogRecorder;
import modi.backend.ingestion.domain.audit.ExternalApi;
import modi.backend.ingestion.domain.audit.ExternalApiCallLog;
import modi.backend.ingestion.domain.audit.ExternalApiOutcome;
import modi.backend.ingestion.properties.CatalogEnrichProperties;
import modi.backend.ingestion.properties.GeminiProperties;

/**
 * AI 장르 분류 축의 서비스 — <b>AI 호출(tx 밖)·AI 콜 로그(model 포함)</b>를 맡는다(설계 §5).
 * 다음 스텝 지식은 없다 — draft 경로의 판정(①)·반영(③)은 {@code ExhibitionDraftService}가, 이벤트 소비 순서는
 * {@code ExhibitionIngestionOrchestrator}가 안다.
 *
 * <p><b>draft당 개별 AI 호출</b>(사용자 확정, ADR-12 — 배치 분류 재도입 금지). 분류 실패는
 * {@code GenreClassificationException}으로 전파돼 이벤트가 RETRYABLE로 남는다(ADR-11 — 시도 소진 없는 무기한 정책).
 *
 * <p><b>AI 콜 로그(신설)</b>: 재설계 전 코드는 AI 호출 감사가 누락돼 있었다(설계 체크리스트 — 호출하면 반드시
 * record). {@code api}는 설정값({@code app.exhibition.genre.classifier}) 기반으로 매핑한다:
 * <ul>
 *   <li>{@code gemini} → {@link ExternalApi#GEMINI} — 실제로는 폴백 체인(1차 Gemini → 2차 OpenAI)이라
 *       <b>대표값</b>이다: 2차로 전환돼 성공한 호출도 GEMINI 한 행으로 남는 한계가 있다(체인 내부 시도 수는
 *       이 로그가 세지 않는다 — 호출 관측은 Spring AI 내장 {@code gen_ai.client.*}가 진다).</li>
 *   <li>{@code claude} → {@link ExternalApi#CLAUDE} — 장르가 Claude 단일 공급자로 전환될 때를 위한 자리.</li>
 *   <li>{@code mock}(기본) → <b>기록 생략</b> — 외부 호출이 없으니 부르지 않은 호출을 남기면 거짓이 쌓인다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ExhibitionAiGenreService {

	/** 장르 분류 전략(AI 체인/mock) — {@code app.exhibition.genre.classifier}로 선택된다(@Primary). */
	private final GenreClassifier genreClassifier;
	/** 외부 호출 감사 — REQUIRES_NEW + 삼킴은 Recorder가 진다. */
	private final ExternalApiCallLogRecorder callLogRecorder;
	/** 1차 공급자 연결 설정 — 콜 로그의 요청 모델 값으로만 쓴다. */
	private final GeminiProperties geminiProperties;
	/** 장르 드레인 유량 정책(배치 크기·실행당 배치 수) — 이 축의 정책이라 파사드가 아니라 여기가 소유한다. */
	private final CatalogEnrichProperties enrichProperties;

	/** 어떤 분류기가 선택돼 있나 — AI 콜 로그의 api 매핑 재료(mock이면 기록 생략). */
	@Value("${app.exhibition.genre.classifier:mock}")
	private String classifier;

	/** 드레인 한 배치의 크기 — 배치당 AI 최대 콜 수와 같다(이벤트당 1콜). */
	public int drainBatchSize() {
		return enrichProperties.genreBatchSize();
	}

	/** 실행당 최대 배치 수 — {@code drainBatchSize() × maxBatchesPerRun()}이 실행당 처리 상한(대량 재백필의 유량 제어)이다. */
	public int maxBatchesPerRun() {
		return enrichProperties.genreMaxBatchesPerRun();
	}

	/**
	 * 장르 분류 <b>단일 시도</b>(tx 밖) — 요청 조립 → AI 호출 → 콜 로그. 실패도 감사에 남기고 그대로 전파한다
	 * (이벤트 수명주기는 호출부가 잇는다 — 장르는 RETRYABLE 고정).
	 *
	 * @param externalId 감사 requestKey(대상 전시 원천키)
	 */
	public GenreResult classify(String externalId, GenreClassification subject) {
		LocalDateTime calledAt = LocalDateTime.now();
		GenreResult result;
		try {
			result = genreClassifier.classify(genreRequest(subject));
		} catch (RuntimeException e) {
			record(externalId, ExternalApiOutcome.FAILED, calledAt);
			throw e;
		}
		record(externalId, ExternalApiOutcome.SUCCESS, calledAt);
		return result;
	}

	/** 이 유스케이스가 분류기에게 무엇을 시킬지 — 표준 지시 + 마스터 전체 허용. 요청 조립은 서비스의 결정이다. */
	private GenreClassificationRequest genreRequest(GenreClassification subject) {
		return new GenreClassificationRequest(
				GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText());
	}

	/** 설정값 기반 api 매핑으로 AI 호출 1건을 감사에 남긴다(mock이면 외부 호출이 없었으니 생략). */
	private void record(String externalId, ExternalApiOutcome outcome, LocalDateTime calledAt) {
		if ("gemini".equalsIgnoreCase(classifier)) {
			callLogRecorder.record(ExternalApiCallLog.ai(ExternalApi.GEMINI, geminiProperties.model(),
					externalId, outcome, calledAt));
			return;
		}
		if ("claude".equalsIgnoreCase(classifier)) {
			callLogRecorder.record(ExternalApiCallLog.ai(ExternalApi.CLAUDE, null,
					externalId, outcome, calledAt));
		}
		// mock — 외부 호출이 일어나지 않았다. 부르지 않은 호출은 감사에 남기지 않는다.
	}
}
