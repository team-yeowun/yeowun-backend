package modi.backend.ingestionv2.collect;

import static modi.backend.ingestionv2.collect.CollectFixtures.catalogItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.collect.domain.CollectCriteria;
import modi.backend.ingestionv2.collect.domain.CollectErrorCode;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.collect.domain.CollectResult;
import modi.backend.ingestionv2.collect.infra.CollectBatchMarkJpaRepository;
import modi.backend.ingestionv2.collect.infra.CollectedExhibitionJpaRepository;
import modi.backend.ingestionv2.collect.infra.ListLedgerJpaRepository;
import modi.backend.support.error.CoreException;

@DisplayName("회차 선점")
class CollectBatchClaimIntegrationTest extends IngestionTestSupport {

	@Autowired private CollectFacade collectFacade;
	@Autowired private CollectBatchMarkJpaRepository batchMarkRepository;
	@Autowired private CollectedExhibitionJpaRepository collectedRepository;
	@Autowired private ListLedgerJpaRepository snapshotRepository;

	@Test
	@DisplayName("같은 시각에 깨어난 두 인스턴스 중 한 쪽만 회차를 선점하고 목록도 한 번만 조회된다")
	void 동시에_깨어난_두_인스턴스_중_하나만_회차를_선점한다() throws Exception {
		// given 두 스레드가 같은 회차로 진입하고, 목록 조회가 몇 번 나갔는지를 스텁이 직접 센다
		LocalDate batchDate = LocalDate.of(2026, 8, 25);
		AtomicInteger fetchCount = new AtomicInteger();
		given(catalogClient.fetchCatalog()).willAnswer(invocation -> {
			fetchCount.incrementAndGet();
			return List.of(catalogItem(vendorKey));
		});

		CyclicBarrier startLine = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		// when 두 스레드를 같은 출발선에 세워 동시에 놓아준다
		List<Future<CollectResult.Batch>> futures = executor.invokeAll(List.of(
				collectTask(startLine, batchDate),
				collectTask(startLine, batchDate)));
		executor.shutdown();

		// then 선점은 한 쪽만, 목록 조회는 한 번, 확정도 한 번
		List<CollectResult.Batch> results = new ArrayList<>();
		for (Future<CollectResult.Batch> future : futures) {
			results.add(future.get(10, TimeUnit.SECONDS));
		}
		assertThat(results).filteredOn(CollectResult.Batch::claimed).hasSize(1);
		assertThat(fetchCount.get()).isEqualTo(1);
		assertThat(batchMarkRepository.count()).isEqualTo(1);
		assertThat(collectedRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("선점에 실패하면 목록을 조회하지 않고 종료한다")
	void 선점에_실패하면_목록을_조회하지_않고_종료한다() {
		// given 같은 회차를 한 번 실행해 마크를 남긴다
		LocalDate batchDate = LocalDate.of(2026, 8, 25);
		AtomicInteger fetchCount = new AtomicInteger();
		given(catalogClient.fetchCatalog()).willAnswer(invocation -> {
			fetchCount.incrementAndGet();
			return List.of(catalogItem(vendorKey));
		});
		collectFacade.collect(CollectCriteria.Batch.of(batchDate));
		int afterFirst = fetchCount.get();

		// when 같은 회차로 다시 실행한다
		CollectResult.Batch result = collectFacade.collect(CollectCriteria.Batch.of(batchDate));

		// then 선점이 막는 것은 저장이 아니라 호출이다
		assertThat(result.claimed()).isFalse();
		assertThat(fetchCount.get()).isEqualTo(afterFirst);
	}

	@Test
	@DisplayName("목록 조회가 실패해도 회차 마크는 남는다")
	void 목록_조회가_실패해도_회차_마크는_남는다() {
		// given 조회 스텁이 예외를 던진다
		LocalDate batchDate = LocalDate.of(2026, 8, 25);
		given(catalogClient.fetchCatalog())
				.willThrow(new CoreException(CollectErrorCode.EXTERNAL_CALL_FAILED, "원천 장애"));

		// when
		assertThatThrownBy(() -> collectFacade.collect(CollectCriteria.Batch.of(batchDate)))
				.isInstanceOf(CoreException.class);

		// then 마크가 남았다는 것은 선점이 조회보다 먼저 커밋되었다는 뜻이다
		assertThat(batchMarkRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("목록 조회가 실패하면 전시가 하나도 확정되지 않는다")
	void 목록_조회가_실패하면_전시가_하나도_확정되지_않는다() {
		// given
		LocalDate batchDate = LocalDate.of(2026, 8, 25);
		given(catalogClient.fetchCatalog())
				.willThrow(new CoreException(CollectErrorCode.EXTERNAL_CALL_FAILED, "원천 장애"));

		// when
		assertThatThrownBy(() -> collectFacade.collect(CollectCriteria.Batch.of(batchDate)))
				.isInstanceOf(CoreException.class);

		// then 반쪽 목록이 확정되는 일이 없다
		assertThat(collectedRepository.count()).isZero();
		assertThat(snapshotRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();
	}

	private Callable<CollectResult.Batch> collectTask(CyclicBarrier startLine, LocalDate batchDate) {
		return () -> {
			startLine.await(10, TimeUnit.SECONDS);
			return collectFacade.collect(CollectCriteria.Batch.of(batchDate));
		};
	}
}
