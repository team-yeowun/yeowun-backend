package modi.backend.ingestionv2.collect;

import static modi.backend.ingestionv2.collect.CollectFixtures.catalogItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import modi.backend.ingestionv2.collect.domain.CollectBatchClaim;
import modi.backend.ingestionv2.collect.domain.CollectBatchStatus;
import modi.backend.ingestionv2.collect.domain.CollectErrorCode;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.collect.domain.CollectResult;
import modi.backend.ingestionv2.collect.domain.CollectService;
import modi.backend.ingestionv2.collect.infra.CollectBatchMarkJpaRepository;
import modi.backend.ingestionv2.collect.infra.CollectedExhibitionJpaRepository;
import modi.backend.ingestionv2.collect.infra.ListLedgerJpaRepository;
import modi.backend.support.error.CoreException;

@DisplayName("회차 선점")
class CollectBatchClaimIntegrationTest extends IngestionTestSupport {

	@Autowired private CollectFacade collectFacade;
	@Autowired private CollectService collectService;
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
	@DisplayName("목록 조회가 실패하면 FAILED로 남고 같은 날짜를 다시 실행할 수 있다")
	void 목록_조회_실패_뒤_같은_회차를_재실행한다() {
		// given 첫 조회는 실패하고 두 번째 조회는 정상 응답한다
		LocalDate batchDate = LocalDate.of(2026, 8, 25);
		given(catalogClient.fetchCatalog())
				.willThrow(new CoreException(CollectErrorCode.EXTERNAL_CALL_FAILED, "원천 장애"))
				.willReturn(List.of(catalogItem(vendorKey)));

		// when 첫 실행이 실패한다
		assertThatThrownBy(() -> collectFacade.collect(CollectCriteria.Batch.of(batchDate)))
				.isInstanceOf(CoreException.class);

		// then 실행 행은 FAILED로 남고, 같은 날짜 재실행이 선점해 완료한다
		assertThat(batchMarkRepository.findByBatchDate(batchDate).orElseThrow().getStatus())
				.isEqualTo(CollectBatchStatus.FAILED);

		CollectResult.Batch retry = collectFacade.collect(CollectCriteria.Batch.of(batchDate));

		assertThat(retry.claimed()).isTrue();
		assertThat(retry.collected()).isEqualTo(1);
		assertThat(batchMarkRepository.findByBatchDate(batchDate).orElseThrow().getStatus())
				.isEqualTo(CollectBatchStatus.COMPLETED);
		assertThat(batchMarkRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("유효한 RUNNING 회차는 막고 lease가 만료되면 같은 날짜를 재선점한다")
	void 실행중인_회차는_lease_만료_뒤에만_재선점한다() {
		// given 한 실행이 회차를 선점한 뒤 아직 완료하지 않았다
		LocalDate batchDate = LocalDate.of(2026, 8, 25);
		CollectBatchClaim first = collectService.claimBatch(batchDate).orElseThrow();
		given(catalogClient.fetchCatalog()).willReturn(List.of(catalogItem(vendorKey)));

		// when lease가 유효한 동안 같은 날짜로 진입한다
		CollectResult.Batch beforeExpiry = collectFacade.collect(CollectCriteria.Batch.of(batchDate));

		// then 외부 목록을 호출하지 않고 종료한다
		assertThat(beforeExpiry.claimed()).isFalse();
		assertThat(collectedRepository.count()).isZero();

		// when 장애로 남은 RUNNING의 lease를 과거로 보내고 다시 진입한다
		jdbcTemplate.update(
				"update ingestion_collect_batch_mark set lease_until = ? where batch_date = ?",
				LocalDateTime.now().minusSeconds(1), batchDate);
		CollectResult.Batch afterExpiry = collectFacade.collect(CollectCriteria.Batch.of(batchDate));

		// then 새 실행이 재선점해 완료하고 이전 token은 더 이상 상태를 바꾸지 못한다
		assertThat(afterExpiry.claimed()).isTrue();
		assertThat(afterExpiry.collected()).isEqualTo(1);
		assertThat(collectService.failBatch(first, "late failure")).isFalse();
		assertThat(batchMarkRepository.findByBatchDate(batchDate).orElseThrow().getStatus())
				.isEqualTo(CollectBatchStatus.COMPLETED);
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
