package modi.backend.ingestionv2.enrich.domain;

import java.util.List;

/**
 * 관리자 조회 포트.
 *
 * <ul>
 *   <li>반영 경로의 EnrichmentRepository와 분리. 잠금 조회가 섞이지 않도록 함</li>
 *   <li>Spring 무의존이라 Pageable 대신 오프셋과 개수를 원시값으로 받음</li>
 *   <li>정렬은 식별자 역순 고정. 보강 루트에는 갱신 시각 컬럼이 없다</li>
 * </ul>
 */
public interface EnrichQueryRepository {

	List<Enrichment> findFailed(int offset, int limit);

	long countFailed();

	long countFailedAtStep(EnrichStep step);
}
