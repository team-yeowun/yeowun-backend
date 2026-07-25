package modi.backend.domain.exhibition.genre;

import java.util.List;

/**
 * 장르 분류 <b>요청</b>(도메인 값). "무엇을 시키고 무엇을 허용하는가"를 호출부가 정해서 분류기에 넘긴다 —
 * 분류기 구현({@link GenreClassifier})은 이 요청을 <b>벤더 호출로 옮기기만</b> 하고 지시나 허용값을 스스로 정하지 않는다.
 * 그래야 provider가 바뀌어도 분류의 정의가 흔들리지 않는다.
 *
 * <p>이 record는 <b>값을 나르기만 한다</b> — 지시 문구를 여기서 만들지 않는다(무엇을 물어볼지는 유스케이스의 결정이라
 * 호출하는 서비스가 정한다). 표준 지시는 {@link GenreInstruction}에 있고, 서비스가 그것을 골라 요청을 조립한다.
 *
 * <p>구성 요소는 셋이다:
 * <ul>
 *   <li>{@link #instruction()} — 분류기에게 주는 지시(프롬프트 주입 가드 포함).</li>
 *   <li>{@link #allowedGenres()} — 허용 값 집합. 구조화 출력의 제약이자 응답 검증 기준이다.</li>
 *   <li>{@link #subject()} — 분류 대상 전시 요약 텍스트({@link GenreClassification#toPromptText()}).</li>
 * </ul>
 *
 * <p>응답 쪽 짝은 {@link GenreResult}다. 순수 자바 값만 노출한다(Spring·HTTP·벤더 어휘 없음).
 */
public record GenreClassificationRequest(String instruction, List<String> allowedGenres, String subject) {

	public GenreClassificationRequest {
		allowedGenres = List.copyOf(allowedGenres);
	}

	/**
	 * 분류기가 낸 값을 이 요청이 받아들이는가(허용 집합 안인가). 검증 기준을 <b>요청이 들고 다니므로</b>
	 * 구현은 전역 마스터를 따로 알 필요가 없다.
	 */
	public boolean accepts(String genre) {
		return genre != null && allowedGenres.contains(genre);
	}
}
