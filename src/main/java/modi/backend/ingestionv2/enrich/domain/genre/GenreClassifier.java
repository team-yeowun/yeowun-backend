package modi.backend.ingestionv2.enrich.domain.genre;

/**
 * 장르 분류 포트.
 *
 * <ul>
 *   <li>폴백 순서와 재시도 간격의 판단은 어댑터 소유. 도메인은 공급자 순서를 모름</li>
 *   <li>전 공급자 소진도 예외가 아니라 결과값. 실패한 시도 목록을 도메인이 받아 기록해야 하기 때문</li>
 *   <li>전시 1건당 개별 분류. 여러 건을 묶어 한 번에 부르는 메서드를 두지 않음</li>
 * </ul>
 */
public interface GenreClassifier {

	GenreResult classify(String title, String description);
}
