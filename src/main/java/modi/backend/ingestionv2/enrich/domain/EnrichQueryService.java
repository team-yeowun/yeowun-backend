package modi.backend.ingestionv2.enrich.domain;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 조회 유스케이스.
 *
 * <ul>
 *   <li>페이지 크기 상한을 이 계층이 강제. 화면이 큰 값을 보내도 여기서 잘림</li>
 *   <li>읽기 전용 트랜잭션 경계를 서비스가 소유. 파사드에는 트랜잭션을 걸지 않음</li>
 *   <li>다른 서비스를 호출하지 않으며 조합은 파사드가 함</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EnrichQueryService {

	/** 한 번에 읽을 수 있는 최대 행 수. */
	private static final int MAX_PAGE_SIZE = 100;

	private final EnrichQueryRepository enrichQueryRepository;

	/** 재시도 상한을 소진한 건을 식별자 역순으로 조회하고 스텝별 집계를 함께 담는다. */
	@Transactional(readOnly = true)
	public EnrichResult.FailedPage findFailed(EnrichCriteria.FailedSearch criteria) {
		int limit = Math.min(Math.max(criteria.size(), 1), MAX_PAGE_SIZE);
		int page = Math.max(criteria.page(), 0);
		List<EnrichResult.Failed> items = enrichQueryRepository.findFailed(page * limit, limit).stream()
				.map(EnrichResult.Failed::from)
				.toList();
		List<EnrichResult.StepCount> stepCounts = Arrays.stream(EnrichStep.values())
				.map(step -> new EnrichResult.StepCount(step, enrichQueryRepository.countFailedAtStep(step)))
				.toList();
		return new EnrichResult.FailedPage(items, page, limit, enrichQueryRepository.countFailed(), stepCounts);
	}
}
