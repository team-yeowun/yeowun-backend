package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 수집 격벽의 유일한 진입점.
 *
 * <ul>
 *   <li>@Transactional 없음 (있으면 아래 세 구간이 한 트랜잭션으로 합쳐짐)</li>
 *   <li>3박자 조율: 선점(tx) → 목록 조회(tx 밖) → 전시별 반영(tx)</li>
 *   <li>전시 1건의 실패를 회차 전체로 번지게 하지 않음</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectFacade {

	private final CollectService collectService;
	private final CultureCatalogClient catalogClient;

	public CollectResult.Batch collect(CollectCriteria.Batch criteria) {
		LocalDate batchDate = criteria.batchDate();
		Optional<CollectBatchClaim> claimed = collectService.claimBatch(batchDate);
		if (claimed.isEmpty()) {
			log.info("수집 회차 선점 생략 batchDate={} (완료됐거나 다른 인스턴스가 수행 중)", batchDate);
			return CollectResult.Batch.notClaimed();
		}
		CollectBatchClaim claim = claimed.get();

		try {
			List<CatalogItem> catalog = catalogClient.fetchCatalog();

			int collected = 0;
			int skipped = 0;
			int failed = 0;
			for (CatalogItem item : catalog) {
				try {
					if (collectService.record(batchDate, item)) {
						collected++;
					} else {
						skipped++;
					}
				} catch (RuntimeException failure) {
					// 트랜잭션 경계 바깥에서만 잡을 수 있다. 안에서 잡으면 이미 롤백 표시된 트랜잭션이라 커밋되지 않는다.
					failed++;
					log.warn("수집 반영 실패 vendorKey={} reason={}", item.vendorKey(), failure.getMessage());
				}
			}

			boolean finalized = failed == 0
					? collectService.completeBatch(claim)
					: collectService.failBatch(claim, "item failures=" + failed);
			if (!finalized) {
				throw new IllegalStateException("수집 회차 실행권이 만료되었거나 다른 실행이 재선점했습니다. batchDate=" + batchDate);
			}
			return CollectResult.Batch.of(collected, skipped, failed);
		} catch (RuntimeException failure) {
			boolean marked = collectService.failBatch(claim, failureSummary(failure));
			if (!marked) {
				log.warn("수집 회차 실패 기록 생략 batchDate={} reason=실행권 변경", batchDate);
			}
			throw failure;
		}
	}

	private String failureSummary(RuntimeException failure) {
		String message = failure.getMessage();
		return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}
