package modi.backend.ingestionv2.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.stage.domain.StageFailureOutcome;
import modi.backend.ingestionv2.stage.domain.StageResult;
import modi.backend.ingestionv2.stage.domain.StagingStatus;

@DisplayName("멱등")
class StageIdempotencyIntegrationTest extends StageTestSupport {

	@Test
	@DisplayName("ST-B1 같은 이벤트가 두 번 와도 전시가 하나만 생긴다")
	void 같은_이벤트가_두_번_와도_전시가_하나만_생긴다() {
		// given
		seedReadyLedger(vendorKey);

		// when
		stageFacade.stage(vendorKey);
		stageFacade.stage(vendorKey);

		// then
		assertThat(coreExhibitionCount(vendorKey)).isEqualTo(1);
	}

	@Test
	@DisplayName("ST-B2 이미 승격된 건이 다시 와도 같은 전시 식별자를 돌려받는다")
	void 이미_승격된_건이_다시_와도_같은_식별자를_돌려받는다() {
		// given
		seedReadyLedger(vendorKey);
		StageResult.Staged first = stageFacade.stage(vendorKey);

		// when
		StageResult.Staged second = stageFacade.stage(vendorKey);

		// then 종결 확인이 빠지면 두 번째가 코어 쓰기를 다시 실행해 유일 제약에 부딪힌다
		assertThat(second.outcome()).isEqualTo(StageResult.Outcome.ALREADY_STAGED);
		assertThat(second.exhibitionId()).isEqualTo(first.exhibitionId());
		assertThat(stagingRow(vendorKey)).containsEntry("attempts", 0);
	}

	@Test
	@DisplayName("ST-B3 같은 전시장의 전시 둘을 승격해도 전시장은 하나만 생긴다")
	void 같은_전시장의_전시_둘을_승격해도_전시장은_하나다() {
		// given 같은 전시장 이름을 가진 두 원천 키
		String second = vendorKey + "-2";
		seedReadyLedger(vendorKey);
		seedListing(second, placeName, "2026-08-01", "2026-12-31");
		seedGenre(second, "회화", "GEMINI");
		seedDetail(second, false);
		seedGooglePlace(second, "{\"weekdayDescriptions\":[\"월요일: 휴관\"]}", false);

		// when
		stageFacade.stage(vendorKey);
		stageFacade.stage(second);

		// then 갈라지면 개장 시간이 그중 한 행에만 붙어 운영 시간이 보였다 안 보였다 한다
		assertThat(corePlaceCount(placeName)).isEqualTo(1);
		assertThat(coreExhibitionCount(vendorKey)).isEqualTo(1);
		assertThat(coreExhibitionCount(second)).isEqualTo(1);
	}

	@Test
	@DisplayName("ST-B4 상한을 소진한 건이 다시 와도 아무 일도 하지 않는다")
	void 상한을_소진한_건이_다시_와도_아무_일도_하지_않는다() {
		// given 상한을 소진해 FAILED 로 멈춘 건
		seedReadyLedger(vendorKey);
		for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
			stageFacade.recordFailure(vendorKey, "실패 " + attempt);
		}
		assertThat(stagingRow(vendorKey)).containsEntry("status", StagingStatus.FAILED.name());

		// when
		StageResult.Staged result = stageFacade.stage(vendorKey);

		// then 관리자가 원인을 확인하기 전에 같은 실패가 반복되지 않는다
		assertThat(result.outcome()).isEqualTo(StageResult.Outcome.ABANDONED);
		assertThat(coreExhibitionCount(vendorKey)).isZero();
		assertThat(stagingRow(vendorKey)).containsEntry("attempts", properties.maxAttempts());
	}

	@Test
	@DisplayName("ST-B5 종결된 건에 실패를 기록해도 상태가 되돌아가지 않는다")
	void 종결된_건에_실패를_기록해도_상태가_되돌아가지_않는다() {
		// given 이미 승격이 끝난 건
		seedReadyLedger(vendorKey);
		stageFacade.stage(vendorKey);
		Object stagedAt = stagingRow(vendorKey).get("staged_at");

		// when 경합에서 진 쪽의 실패 기록이 뒤늦게 도착한다
		StageFailureOutcome outcome = stageFacade.recordFailure(vendorKey, "뒤늦은 실패");

		// then 가드가 없으면 방금 확정된 종결이 PENDING 으로 되돌아가고 전부 다시 실행된다
		assertThat(outcome).isEqualTo(StageFailureOutcome.ALREADY_SETTLED);
		assertThat(stagingRow(vendorKey))
				.containsEntry("status", StagingStatus.STAGED.name())
				.containsEntry("attempts", 0)
				.containsEntry("staged_at", stagedAt);

		// and 핸들러 경로로 같은 상황을 만들어도 예외가 나지 않는다(항목이 걷어내진다)
		assertThatCode(() -> handle(IngestionEventType.INSPECTED, vendorKey)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("원장이 결손된 채 승격을 시도하면 코어에 아무것도 남지 않는다")
	void 원장이_결손되면_코어에_아무것도_남지_않는다() {
		// given 목록 원장이 없다
		seedGenre(vendorKey, "회화", "GEMINI");

		// when & then
		assertThatThrownBy(() -> stageFacade.stage(vendorKey)).isInstanceOf(RuntimeException.class);
		assertThat(coreExhibitionCount(vendorKey)).isZero();
	}
}
