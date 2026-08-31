package modi.backend.ingestionv2.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.stage.domain.StageErrorCode;
import modi.backend.ingestionv2.stage.domain.StageResult;
import modi.backend.ingestionv2.stage.domain.StagingStatus;
import modi.backend.support.error.CoreException;

@DisplayName("관리자 재시도와 조회")
class AdminStageIntegrationTest extends StageTestSupport {

	@Test
	@DisplayName("ST-E4 관리자 재시도는 FAILED 만 되돌린다")
	void 관리자_재시도는_FAILED만_되돌린다() {
		// given 상한을 소진해 멈춘 건
		seedReadyLedger(vendorKey);
		exhaust(vendorKey);

		// when
		StageResult.Reopened reopened = stageFacade.reopen(vendorKey);

		// then
		assertThat(reopened.vendorKey()).isEqualTo(vendorKey);
		assertThat(stagingRow(vendorKey))
				.containsEntry("status", StagingStatus.PENDING.name())
				.containsEntry("attempts", 0);
	}

	@Test
	@DisplayName("ST-E4 종결된 건과 진행 중인 건은 재시도를 거절한다")
	void 종결된_건과_진행_중인_건은_재시도를_거절한다() {
		// given 이미 승격이 끝난 건
		seedReadyLedger(vendorKey);
		stageFacade.stage(vendorKey);

		// when & then 관리자의 실수 한 번이 데이터 사고가 되지 않는다
		assertThatThrownBy(() -> stageFacade.reopen(vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(StageErrorCode.INVALID_STAGE_TRANSITION);
	}

	@Test
	@DisplayName("ST-E4 없는 원천 키는 찾을 수 없다는 오류다")
	void 없는_원천_키는_찾을_수_없다는_오류다() {
		assertThatThrownBy(() -> stageFacade.reopen("존재하지-않는-키"))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(StageErrorCode.STAGING_NOT_FOUND);
	}

	@Test
	@DisplayName("ST-E5 관리자 실패 목록이 최근 실패 순으로 조회되고 크기가 잘린다")
	void 실패_목록이_최근_순으로_조회되고_크기가_잘린다() {
		// given 실패 건 둘(나중 것이 더 최근)
		seedReadyLedger(vendorKey);
		exhaust(vendorKey);
		String second = vendorKey + "-2";
		seedListing(second, placeName + "-2", "2026-08-01", "2026-12-31");
		seedGenre(second, "회화", "GEMINI");
		exhaust(second);

		// when
		StageResult.FailedPage page = stageFacade.findFailed(0, 500);

		// then 정렬이 어긋나면 방금 실패한 건이 목록 뒤쪽에 묻힌다
		assertThat(page.size()).isEqualTo(100);
		assertThat(page.totalCount()).isEqualTo(2);
		assertThat(page.items()).extracting(StageResult.Failed::vendorKey)
				.containsExactly(second, vendorKey);
		assertThat(page.items()).allMatch(failed -> failed.attempts() == properties.maxAttempts());
	}

	/** 상한을 소진해 FAILED 로 만든다. */
	private void exhaust(String key) {
		for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
			stageFacade.recordFailure(key, "실패 " + attempt);
		}
	}
}
