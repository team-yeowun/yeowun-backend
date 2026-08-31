package modi.backend.ingestionv2.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;

@DisplayName("정리 배치")
class OutboxCleanupTest extends DeliveryTestSupport {

	@Test
	@DisplayName("보존 기간이 지난 발행 완료 행을 지운다")
	void 보존_기간이_지난_종결_행을_지운다() {
		// given 적재 시각을 보존 기간보다 앞으로 밀어 둔 SENT 행
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		drainAll();
		ageCreatedAt();

		// when
		int deleted = outboxService.cleanupSent(
				IngestionClock.now().minusDays(properties.retentionDays()), properties.cleanupBatchSize());

		// then
		assertThat(deleted).isEqualTo(1);
		assertThat(outboxRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("배정 스트림이 없던 행도 지워진다")
	void 배정_스트림이_없던_행도_지워진다() {
		// given 스트림을 거치지 않고 SENT로 닫힌 오래된 행
		outboxAppender.append(IngestionEventType.STAGED, vendorKey);
		outboxDispatcher.dispatchPending();
		assertThat(outboxRepository.findAll().getFirst().getRetryCount()).isZero();
		ageCreatedAt();

		// when
		int deleted = outboxService.cleanupSent(
				IngestionClock.now().minusDays(properties.retentionDays()), properties.cleanupBatchSize());

		// then 정리 기준은 적재 시각이다. 발행 여부와 무관하게 SENT 면 보존 기간 뒤에 지워진다.
		assertThat(deleted).isEqualTo(1);
		assertThat(outboxRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("보존 기간 전 발행 완료 행은 남는다")
	void 보존_기간_전_종결_행은_남는다() {
		// given 방금 발행 완료된 행
		outboxAppender.append(IngestionEventType.COLLECTED, vendorKey);
		drainAll();

		// when
		int deleted = outboxService.cleanupSent(
				IngestionClock.now().minusDays(properties.retentionDays()), properties.cleanupBatchSize());

		// then
		assertThat(deleted).isZero();
		assertThat(outboxRepository.findAll()).hasSize(1);
	}

	private void ageCreatedAt() {
		jdbcTemplate.update("update ingestion_outbox set created_at = ?",
				IngestionClock.now().minusDays(properties.retentionDays() + 1L));
	}
}
