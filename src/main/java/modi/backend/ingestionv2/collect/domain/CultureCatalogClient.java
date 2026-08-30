package modi.backend.ingestionv2.collect.domain;

import java.util.List;

/**
 * 문화포털 목록 조회 포트.
 *
 * <ul>
 *   <li>호출은 항상 트랜잭션 밖 (구현체에 @Transactional 없음)</li>
 *   <li>반환은 수집 격벽의 어휘 (벤더 DTO가 경계를 넘지 않음)</li>
 *   <li>실패는 예외로 전파 (회차가 실패로 끝나야 하므로 삼키지 않음)</li>
 * </ul>
 */
public interface CultureCatalogClient {

	List<CatalogItem> fetchCatalog();
}
