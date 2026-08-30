package modi.backend.ingestionv2.enrich.domain.genre;

import modi.backend.ingestionv2.enrich.domain.EnrichErrorCode;
import modi.backend.support.error.CoreException;

/**
 * 장르 분류가 전 공급자에서 실패했음을 알리는 예외.
 *
 * <ul>
 *   <li>실패한 시도 목록을 그대로 들고 다님. 핸들러가 그 값으로 하위 엔티티를 채운다</li>
 *   <li>어댑터는 이 예외를 던지지 않음. 결과값을 보고 파사드가 만든다</li>
 *   <li>접근자를 record와 같은 이름으로 직접 선언. 호출부가 result()로 읽는다</li>
 * </ul>
 */
public class GenreClassifyFailedException extends CoreException {

	private final transient GenreResult result;

	public GenreClassifyFailedException(GenreResult result) {
		super(EnrichErrorCode.GENRE_CLASSIFY_FAILED, result.failureSummary());
		this.result = result;
	}

	public GenreResult result() {
		return result;
	}
}
