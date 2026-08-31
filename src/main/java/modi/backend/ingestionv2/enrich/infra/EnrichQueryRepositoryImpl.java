package modi.backend.ingestionv2.enrich.infra;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.EnrichQueryRepository;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.Enrichment;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStatus;
import modi.backend.ingestionv2.enrich.domain.StepStatus;

/**
 * 관리자 조회 어댑터.
 *
 * <ul>
 *   <li>idx_ingestion_enrichment_status를 그대로 타도록 조건과 정렬을 맞춤</li>
 *   <li>하위 셋을 함께 가져오도록 조인 페치. 목록 한 페이지에 추가 조회가 붙지 않게 함</li>
 *   <li>스텝별 집계는 스텝마다 질의가 다르므로 세 메서드로 나눠 둔 것을 여기서 고른다</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class EnrichQueryRepositoryImpl implements EnrichQueryRepository {

	private final EnrichmentJpaRepository jpaRepository;

	@Override
	public List<Enrichment> findFailed(int offset, int limit) {
		int page = limit == 0 ? 0 : offset / limit;
		return jpaRepository.findByStatusWithSteps(EnrichmentStatus.FAILED, PageRequest.of(page, limit));
	}

	@Override
	public long countFailed() {
		return jpaRepository.countByStatus(EnrichmentStatus.FAILED);
	}

	@Override
	public long countFailedAtStep(EnrichStep step) {
		return switch (step) {
			case DETAIL -> jpaRepository.countDetailAtStatus(EnrichmentStatus.FAILED, StepStatus.FAILED);
			case GENRE -> jpaRepository.countGenreAtStatus(EnrichmentStatus.FAILED, StepStatus.FAILED);
			case HOURS -> jpaRepository.countHoursAtStatus(EnrichmentStatus.FAILED, StepStatus.FAILED);
		};
	}
}
