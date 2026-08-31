package modi.backend.ingestionv2.stage.domain;

import java.util.List;

/**
 * 관리자 조회 포트.
 *
 * <ul>
 *   <li>반영 경로의 StageRepository 와 분리, 잠금 조회가 섞이지 않도록 함</li>
 *   <li>Spring 무의존이라 Pageable 대신 오프셋과 개수를 원시값으로 받음</li>
 *   <li>정렬은 마지막 실패 시각 내림차순 고정</li>
 * </ul>
 */
public interface StageQueryRepository {

	List<Staging> findByStatus(StagingStatus status, int offset, int limit);

	long countByStatus(StagingStatus status);
}
