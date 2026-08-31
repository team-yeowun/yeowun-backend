package modi.backend.ingestionv2.enrich.infra.ai;

import modi.backend.domain.exhibition.genre.GenreProvider;

/**
 * 공급자 하나에 대한 단일 시도 창구. 구현체의 등록 순서가 곧 폴백 순서다.
 *
 * <ul>
 *   <li>공급자당 단일 시도 - SDK·프레임워크 재시도는 끈다(재시작을 넘는 재시도는 배달 계층의 몫)</li>
 *   <li>api-key 미설정이면 isConfigured가 거짓 - 부르지 않은 호출을 감사에 남기지 않는다</li>
 * </ul>
 */
public interface GenreVendorClient {

	boolean isConfigured();

	GenreProvider provider();

	String model();

	/** 분류 한 번. 실패는 예외로 던지고 체인이 다음 공급자로 넘어간다. */
	String classify(String title, String description);
}
