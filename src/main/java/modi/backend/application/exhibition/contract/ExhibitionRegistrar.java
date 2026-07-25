package modi.backend.application.exhibition.contract;

import java.time.LocalDateTime;

/**
 * 전시 등록 계약 — 수집(ingestion)이 완성한 전시 한 건을 코어에 등록하는 유일한 통로(ADR-12).
 * DRAFT_READY 이벤트의 소비 측에서 호출되며, at-least-once 재전달을 전제로 <b>멱등</b>이어야 한다:
 * 같은 원천({@code external_id})이 이미 등록돼 있으면 새로 만들지 않고 그 전시로 응답한다(UK가 최후의 가드).
 */
public interface ExhibitionRegistrar {

	/**
	 * 전시 + 부속(상세 satellite·전시장 보강·장르 정준행)을 한 트랜잭션으로 등록한다.
	 * 호출 측 트랜잭션이 있으면 합류한다(REQUIRED) — 소비 측이 [등록 + 진행 상태 종료]를 원자로 묶을 수 있다.
	 *
	 * @return 등록된(또는 이미 있던) 전시 id
	 */
	Registered register(ExhibitionRegistration registration, LocalDateTime now);

	/** 등록 결과 — 전시 id. (place_key 반환은 영업시간 재검증 폐기(설계 D4)로 함께 삭제됐다.) */
	record Registered(long exhibitionId) {
	}
}
