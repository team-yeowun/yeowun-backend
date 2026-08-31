package modi.backend.ingestionv2.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.stage.domain.StageResult;
import modi.backend.ingestionv2.stage.domain.StagingStatus;

@DisplayName("원자성")
class StageAtomicityIntegrationTest extends StageTestSupport {

	@Test
	@DisplayName("ST-A1 조립이 실패하면 코어에 아무것도 남지 않는다")
	void 조립이_실패하면_코어에_아무것도_남지_않는다() {
		// given 장르 원장이 없어 조립이 멈춘다
		seedListing(vendorKey, placeName, "2026-08-01", "2026-12-31");
		seedDetail(vendorKey, false);
		seedGooglePlace(vendorKey, null, true);

		// when
		assertThatThrownBy(() -> stageFacade.stage(vendorKey)).isInstanceOf(RuntimeException.class);

		// then 전시는 있는데 개장 시간이 없는 반쪽 상태를 만들지 않는다
		assertThat(coreExhibitionCount(vendorKey)).isZero();
		assertThat(corePlaceCount(placeName)).isZero();
	}

	@Test
	@DisplayName("ST-A2 승격이 성공하면 코어 세 곳과 상태가 모두 반영된다")
	void 승격이_성공하면_코어_세_곳과_상태가_모두_반영된다() {
		// given
		seedReadyLedger(vendorKey);

		// when
		StageResult.Staged staged = stageFacade.stage(vendorKey);

		// then 다섯 중 하나라도 빠지면 커밋된 것이 반쪽이다
		assertThat(staged.outcome()).isEqualTo(StageResult.Outcome.REGISTERED);
		assertThat(coreExhibitionCount(vendorKey)).isEqualTo(1);
		assertThat(corePlaceCount(placeName)).isEqualTo(1);
		assertThat(corePlaceHoursCount(placeName)).isEqualTo(1);
		assertThat(stagingRow(vendorKey))
				.containsEntry("status", StagingStatus.STAGED.name())
				.hasEntrySatisfying("staged_exhibition_id", value -> assertThat(value).isNotNull())
				.hasEntrySatisfying("staged_at", value -> assertThat(value).isNotNull());
	}

	@Test
	@DisplayName("ST-A3 반영이 롤백되어도 실패 기록은 남는다")
	void 반영이_롤백되어도_실패_기록은_남는다() {
		// given 조립이 실패하는 상황(장르 원장 없음)
		seedListing(vendorKey, placeName, "2026-08-01", "2026-12-31");

		// when 핸들러 경로로 실행한다(실패 기록은 REQUIRES_NEW 로 별도 트랜잭션에서 돈다)
		assertThatThrownBy(() -> handle(modi.backend.ingestionv2.common.event.IngestionEventType.INSPECTED,
				vendorKey)).isInstanceOf(RuntimeException.class);

		// then 기록이 함께 롤백되면 시도 횟수가 영원히 늘지 않아 상한 판정이 성립하지 않는다
		assertThat(stagingRow(vendorKey))
				.containsEntry("attempts", 1)
				.containsEntry("status", StagingStatus.PENDING.name())
				.hasEntrySatisfying("last_error", value -> assertThat(value).isNotNull());
	}
}
