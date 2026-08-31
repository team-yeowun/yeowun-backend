package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxClaimStrategy;
import modi.backend.ingestionv2.common.outbox.OutboxReadSource;

/**
 * 두 발송기가 같은 순간에 선점했을 때 전략별로 무엇을 받는지 고정한다.
 *
 * <ul>
 *   <li>스레드 둘의 실경합(인스턴스 두 대가 아니다) - 두 트랜잭션이 동시에 열린 상태를 관문으로 만들어 두므로
 *       결과가 실행 속도에 좌우되지 않는다</li>
 *   <li>NONE 은 같은 행 집합을 두 번 내준다 - 비교 실험의 기준선이 실제로 중복의 씨앗이라는 확인</li>
 *   <li>SKIP_LOCKED 는 두 트랜잭션에 겹치지 않는 집합을 준다 - 한 행이 두 곳으로 가지 않는다는 확인.
 *       어떻게 나뉘는지는 고정하지 않는다 - 두 조회가 시간상 겹치면 각자 상대가 잡지 않은 행을 이어 집으므로
 *       분배 비율은 조회 시작 간격과 데이터 양에 따라 달라진다 - 그 비율이 부하 실험의 관측 대상이다</li>
 *   <li>PESSIMISTIC 은 이 구조로 재지 않는다 - 뒤에 온 쪽이 잠금에서 멈춰 관문에 닿지 못한다.
 *       그 대기 자체가 부하 실험의 관측 대상이다</li>
 * </ul>
 */
class OutboxClaimConcurrencyTest extends IngestionTestSupport {

	private static final int ROWS = 10;

	@Test
	@DisplayName("무제어 선점은 두 트랜잭션에 같은 행을 내준다")
	void 무제어_선점은_같은_행을_두_번_내준다() throws Exception {
		// given
		seed();

		// when
		List<List<Long>> claimed = claimTogether(OutboxClaimStrategy.NONE);

		// then 양쪽이 같은 행을 그대로 받는다
		assertThat(claimed.get(0)).hasSize(ROWS);
		assertThat(claimed.get(1)).containsExactlyElementsOf(claimed.get(0));
	}

	@Test
	@DisplayName("SKIP LOCKED 선점은 같은 행을 두 곳에 내주지 않는다")
	void 건너뛰기_선점은_같은_행을_두_곳에_내주지_않는다() throws Exception {
		// given
		seed();

		// when
		List<List<Long>> claimed = claimTogether(OutboxClaimStrategy.SKIP_LOCKED);

		// then 둘을 합치면 전부이고 겹치는 행은 없다. 한쪽이 빈손일 수도, 반씩 갈릴 수도 있다 - 그 비율은 고정하지 않는다.
		List<Long> both = new ArrayList<>(claimed.get(0));
		both.addAll(claimed.get(1));
		assertThat(both).hasSize(ROWS).doesNotHaveDuplicates();
	}

	private void seed() {
		for (int index = 0; index < ROWS; index++) {
			outboxAppender.append(IngestionEventType.COLLECTED, vendorKey + "-" + index);
		}
	}

	/** 두 트랜잭션을 동시에 열어 둔 채로 각자 선점하게 한다. 둘 다 롤백해 상태를 남기지 않는다. */
	private List<List<Long>> claimTogether(OutboxClaimStrategy strategy) throws Exception {
		CyclicBarrier bothClaimed = new CyclicBarrier(2);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			Callable<List<Long>> claim = () -> transactionTemplate.execute(status -> {
				List<Outbox> rows = outboxService.claimPending(0, strategy, OutboxReadSource.MASTER);
				List<Long> ids = rows.stream().map(Outbox::getId).toList();
				await(bothClaimed);
				status.setRollbackOnly();
				return ids;
			});
			Future<List<Long>> first = workers.submit(claim);
			Future<List<Long>> second = workers.submit(claim);
			return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
		} finally {
			workers.shutdownNow();
		}
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(30, TimeUnit.SECONDS);
		} catch (Exception interrupted) {
			throw new IllegalStateException("두 선점이 동시에 열리지 않았습니다.", interrupted);
		}
	}
}
