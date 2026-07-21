package modi.backend.ingestion.domain.entity;

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
import modi.backend.ingestion.domain.ExternalApi;
import modi.backend.ingestion.domain.ExternalApiOutcome;

/**
 * 외부 호출 감사(append-only) — {@code external_api_call_log} 매핑.
 *
 * <p><b>벤더가 늘어도 이 테이블 하나다.</b> 문화포털·Gemini·구글이 각자 다른 어휘를 쓰지만 "언제 무엇을 불렀고
 * 어떻게 끝났나"는 공통이라, 처음부터 벤더·모델 불문으로 설계됐다(ERD 3장). 카카오·Claude가 와도
 * {@link ExternalApi} 값만 는다.
 *
 * <p>무엇을 푸나: "오늘 구글을 몇 번 불렀나", "폴백이 왜 늘었나"(429 추이), "동기화가 왜 느린가"(상세 호출 수)를
 * 로그 grep이 아니라 질의로 답한다.
 *
 * <p><b>비용 귀속(billable)은 하지 않는다</b>(사용자 결정). 유료 여부는 {@link ExternalApi} 값에서 이미 나오므로
 * 행마다 들고 다닐 이유가 없었고, 실제로 <b>기록만 되고 아무도 읽지 않는 컬럼</b>이었다. 비용을 다시 보고 싶어지면
 * api 축으로 집계해라 — 그게 원래 이 테이블의 인덱스({@code api, called_at})가 지원하던 질의다.
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

	@Enumerated(EnumType.STRING)
	@Column(name = "api", nullable = false, length = 30)
	private ExternalApi api;

	/** AI 호출만 — <b>요청</b> 모델(설정값). 실제 서빙 모델은 응답 modelVersion이라 정준층({@code exhibition_genre.model})에 남는다. */
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

	private ExternalApiCallLog(ExternalApi api, String model, String requestKey, ExternalApiOutcome outcome,
			LocalDateTime calledAt) {
		this.api = api;
		this.model = model;
		this.requestKey = requestKey;
		this.outcome = outcome;
		this.calledAt = calledAt;
	}

	/** 호출 1건 — 대상 식별자(external_id·place_key·page 등)를 남긴다. */
	public static ExternalApiCallLog of(ExternalApi api, String requestKey, ExternalApiOutcome outcome,
			LocalDateTime calledAt) {
		return new ExternalApiCallLog(api, null, requestKey, outcome, calledAt);
	}

	/** AI 호출 1건 — 요청 모델을 함께 남긴다(모델별 호출량·429 비율 집계용). */
	public static ExternalApiCallLog ai(ExternalApi api, String model, ExternalApiOutcome outcome,
			LocalDateTime calledAt) {
		return new ExternalApiCallLog(api, model, null, outcome, calledAt);
	}
}
