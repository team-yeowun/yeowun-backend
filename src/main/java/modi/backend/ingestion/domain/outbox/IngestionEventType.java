package modi.backend.ingestion.domain.outbox;

/**
 * 전시 아웃박스({@link OutboxMessage})가 나르는 <b>이벤트</b>의 종류 — "내가 한 일"(발행자의 사실)이지
 * "다음에 할 일"(커맨드)이 아니다(설계 §1). 각 이벤트의 발행자는 정확히 하나의 서비스이고, 이벤트를 소비해
 * 다음 스텝을 실행하는 매핑은 {@code ExhibitionIngestionOrchestrator} 한 곳에만 있다 — 커맨드 어휘를 쓰면
 * 파이프라인 순서 지식이 서비스마다 번져 오케스트레이션이 껍데기가 된다.
 *
 * <p>진행 상태 요구사항(at-least-once·수동 재시도·비용상한·재검증)은 축마다 3벌 구현하는 대신, 이 enum으로
 * 갈라지는 <b>한 테이블</b>({@code exhibition_outbox})에 모은다(설계 §2). 컬럼명({@code message_type})·UK는
 * 메커니즘(우체국)의 어휘라 그대로 두고, 이벤트 의미론은 이 타입이 담당한다(설계 §7 — 편지 내용과 우체국의 분리).
 *
 * <p>{@code target_key}의 의미가 종류마다 다르다: draft 계열은 전시 원천키({@code external_id}), 영업시간 계열은
 * 장소키({@code place_key})다. 두 키 공간이 UK{@code (message_type, target_key)} 안에서 종류로 분리되므로 충돌하지 않는다.
 */
public enum IngestionEventType {

	/**
	 * draft가 스테이징됐다 — {@code target_key = external_id}. 발행: {@code ExhibitionDraftService.stageFromList}.
	 * 소비 시 다음 스텝: 상세(detail2) 조회 3박자.
	 */
	DRAFT_STAGED("상세"),

	/**
	 * 상세 스텝이 해소됐다(값 도착·무상세 확인) — {@code target_key = external_id}.
	 * 발행: {@code ExhibitionDraftService.applyDetail/markDetailAbsent}.
	 * 소비 시 다음 스텝: AI 장르 분류 3박자. AI 장애 시 RETRYABLE로 남아 회복 후 자동 재분류("AI 최소 1회 무조건").
	 */
	DETAIL_FETCHED("전시 장르"),

	/**
	 * draft가 승격 게이트를 채웠다 — {@code target_key = external_id}. 게이트를 채운 마지막 스텝 트랜잭션이
	 * <b>원자 기록</b>하고(발행: {@code ExhibitionDraftService}의 게이트 검사), 소비 측이 draft를 재조회해 코어 등록
	 * 계약({@code ExhibitionRegistrar})을 호출한다(ADR-12 — payload 없음, 재조회 방식이라 스키마도 불변).
	 * 등록은 {@code exhibitions.external_id} UK로 멱등이라 재전달에 안전하다.
	 */
	DRAFT_READY("승격"),

	/**
	 * 기존 장소의 영업시간이 낡았을 수 있다 — {@code target_key = place_key}. 새 전시가 기존 장소에 유입될 때
	 * 이벤트로 enqueue된다(설계 §4-1). 소비 시 다음 스텝: 재검증 조회.
	 */
	PLACE_HOURS_STALE("영업시간");

	/** 이 이벤트를 소비하면 무슨 스텝인가 — 운영 로그의 어휘다. 이벤트가 자기 이름을 알아야 호출부가 라벨을 들고 다니지 않는다. */
	private final String stepLabel;

	IngestionEventType(String stepLabel) {
		this.stepLabel = stepLabel;
	}

	public String stepLabel() {
		return stepLabel;
	}
}
