package modi.backend.domain.exhibition.genre;



/**
 * 전시 장르 분류 포트(도메인 소유, 구현 무관). 애플리케이션은 이 인터페이스만 의존하고,
 * 실제 전략(AI 체인/mock)은 구현체가 담당한다 — {@code app.exhibition.genre.classifier}로 교체(DIP).
 *
 * <p><b>계약(ADR-11 — 반전됨)</b>: 반환 {@link GenreResult#genreKeyword()}는 <b>항상</b> {@link GenreKeyword}
 * 마스터 중 하나이며, 유효한 분류를 만들지 못하면 <b>{@link GenreClassificationException}을 던진다</b>
 * (폴백값 반환 금지). 과거 계약("어떤 경우에도 유효 장르 반환")은 실패를 랜덤 폴백값으로 가려
 * 호출부가 provider 표식으로 되분류하는 우회를 낳았다 — 이제 실패는 아웃박스 메시지 RETRYABLE로 남아
 * durable 재시도되고, draft는 분류될 때까지 승격을 대기한다.
 *
 * <p>2차 공급자 전환은 구현(폴백 체인)의 몫이고, 재시작을 넘는 durable 재시도는 아웃박스 폴러의 몫이다 —
 * 두 계층을 섞지 않는다(ADR-10).
 */
public interface GenreClassifier {

	/**
	 * 전시 정보를 장르 마스터 중 1개로 분류한다.
	 *
	 * @throws GenreClassificationException 유효한 분류를 만들지 못했을 때(미설정·한도 초과·오류·마스터 이탈)
	 */
	GenreResult classify(GenreClassification input);
}
