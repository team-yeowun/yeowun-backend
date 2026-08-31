package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;
import modi.backend.ingestionv2.common.outbox.OutboxClaimStrategy;
import modi.backend.ingestionv2.common.outbox.OutboxReadSource;

/**
 * 선점 전략 네 값이 같은 조건·같은 정렬로 실행되는지 고정한다.
 *
 * <ul>
 *   <li>네이티브 쿼리라 문법 오류가 기동 시점에 드러나지 않는다 - 여덟 갈래(전략 4 × 상한 유무)를
 *       전부 한 번씩 실행해 두는 것이 이 테스트의 목적</li>
 *   <li>상한 0 이 실제로 "상한 없음"인지도 여기서 고정한다</li>
 * </ul>
 */
class OutboxClaimStrategyTest extends IngestionTestSupport {

	private static final int SEEDED = 5;

	@ParameterizedTest
	@EnumSource(OutboxClaimStrategy.class)
	@DisplayName("전략 넷 모두 상한만큼 집는다")
	void 전략_넷_모두_상한만큼_집는다(OutboxClaimStrategy strategy) {
		// given
		seed();

		// when
		List<Outbox> claimed = claim(3, strategy);

		// then
		assertThat(claimed).hasSize(3);
	}

	@ParameterizedTest
	@EnumSource(OutboxClaimStrategy.class)
	@DisplayName("상한 0은 미발행 행을 전부 집는다")
	void 상한_0은_전부_집는다(OutboxClaimStrategy strategy) {
		// given
		seed();

		// when
		List<Outbox> claimed = claim(0, strategy);

		// then
		assertThat(claimed).hasSize(SEEDED);
	}

	@Test
	@DisplayName("선점은 오래된 순으로 집는다")
	void 선점은_오래된_순으로_집는다() {
		// given
		seed();

		// when
		List<Outbox> claimed = claim(0, OutboxClaimStrategy.SKIP_LOCKED);

		// then
		assertThat(claimed).isSortedAccordingTo((left, right) -> {
			int byCreatedAt = left.getCreatedAt().compareTo(right.getCreatedAt());
			return byCreatedAt != 0 ? byCreatedAt : Long.compare(left.getId(), right.getId());
		});
	}

	private void seed() {
		for (int index = 0; index < SEEDED; index++) {
			outboxAppender.append(IngestionEventType.COLLECTED, vendorKey + "-" + index);
		}
	}

	private List<Outbox> claim(int limit, OutboxClaimStrategy strategy) {
		return transactionTemplate.execute(status -> {
			List<Outbox> claimed = outboxService.claimPending(limit, strategy, OutboxReadSource.MASTER);
			status.setRollbackOnly();
			return claimed;
		});
	}
}
