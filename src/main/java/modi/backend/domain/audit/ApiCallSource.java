package modi.backend.domain.audit;

/**
 * 외부 호출을 일으킨 <b>기능 축</b>({@code external_api_call_log.source}) — "어느 기능이 이 비용을 태웠나".
 *
 * <p>{@link ExternalApi}(어느 벤더를 불렀나)와 직교한다: 같은 CLAUDE라도 수집(장르 분류)·감상문·리마인드가
 * 각자 부를 수 있고, 비용 귀속과 장애 영향 범위는 벤더가 아니라 기능 축으로 갈린다. 공용화(설계 D3)의
 * 이유가 이 컬럼이다 — 테이블이 ingestion 소유일 때는 소유자가 자명해 없어도 됐다.
 */
public enum ApiCallSource {

	/** 전시 수집 파이프라인(목록·상세·AI 장르·구글 영업시간). */
	INGESTION,

	/** 감상문 AI(구조화 완성). */
	RECORD,

	/** 리마인드 감정 요약 AI. */
	REMIND
}
