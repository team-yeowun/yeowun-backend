package modi.backend.domain.audit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 호출 감사(append-only) — {@code external_api_call_log} 매핑. <b>공용 감사</b>다(설계 D3 — 구 ingestion 소유).
 *
 * <p><b>벤더가 늘어도 이 테이블 하나다.</b> 문화포털·Gemini·구글·Claude가 각자 다른 어휘를 쓰지만 "언제 무엇을
 * 불렀고 어떻게 끝났나"는 공통이라, 처음부터 벤더·모델 불문으로 설계됐다(ERD 3장). 기능 축이 늘면
 * {@link ApiCallSource} 값만 늘고, 벤더가 늘면 {@link ExternalApi} 값만 는다.
 *
 * <p>무엇을 푸나: "오늘 구글을 몇 번 불렀나", "폴백이 왜 늘었나"(429 추이), "동기화가 왜 느린가"(상세 호출 수),
 * "이 비용은 어느 기능이 태웠나"(source 축)를 로그 grep이 아니라 질의로 답한다.
 *
 * <p>멱등 대상이 아니다 — 호출은 <b>이벤트</b>라 같은 대상을 두 번 부르면 두 행이다(UK 없음).
 * 그래서 재시도 3회는 3행이 되고, 그게 정확한 사실이다(한도는 시도 횟수로 발생한다).
 */
@Entity
@Table(name = "external_api_call_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExternalApiCallLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 이 호출을 일으킨 기능 축 — 비용 귀속·장애 영향 범위의 조회 축(공용화의 이유). */
	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, length = 20)
	private ApiCallSource source;

	@Enumerated(EnumType.STRING)
	@Column(name = "api", nullable = false, length = 30)
	private ExternalApi api;

	/** AI 호출만 — <b>요청</b> 모델(설정값). 실제 서빙 모델은 응답 modelVersion이라 정준층에 남는다. */
	@Column(name = "model", length = 50)
	private String model;

	/** 호출 대상 식별(external_id·place_key·page 등). 배치 호출처럼 대상이 하나가 아니면 null. */
	@Column(name = "request_key", length = 500)
	private String requestKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false, length = 20)
	private ExternalApiOutcome outcome;

	@Column(name = "called_at")
	private LocalDateTime calledAt;

	private ExternalApiCallLog(ApiCallSource source, ExternalApi api, String model, String requestKey,
			ExternalApiOutcome outcome, LocalDateTime calledAt) {
		this.source = source;
		this.api = api;
		this.model = model;
		this.requestKey = requestKey;
		this.outcome = outcome;
		this.calledAt = calledAt;
	}

	/** 호출 1건 — 대상 식별자(external_id·place_key·page 등)를 남긴다. */
	public static ExternalApiCallLog of(ApiCallSource source, ExternalApi api, String requestKey,
			ExternalApiOutcome outcome, LocalDateTime calledAt) {
		return new ExternalApiCallLog(source, api, null, requestKey, outcome, calledAt);
	}

	/** AI 호출 1건 — 요청 모델을 함께 남긴다(모델별 호출량·429 비율 집계용). */
	public static ExternalApiCallLog ai(ApiCallSource source, ExternalApi api, String model, String requestKey,
			ExternalApiOutcome outcome, LocalDateTime calledAt) {
		return new ExternalApiCallLog(source, api, model, requestKey, outcome, calledAt);
	}
}
