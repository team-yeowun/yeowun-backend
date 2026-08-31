package modi.backend.ingestionv2.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichCriteria;
import modi.backend.ingestionv2.enrich.domain.EnrichErrorCode;
import modi.backend.ingestionv2.enrich.domain.EnrichResult;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStatus;
import modi.backend.ingestionv2.enrich.domain.StepStatus;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;
import modi.backend.support.error.CoreException;

@DisplayName("되돌리기가 실패한 스텝만 다시 연다")
class EnrichAdminReopenIntegrationTest extends EnrichTestSupport {

	@Test
	@DisplayName("실패 목록은 스텝별 현황과 전체 집계를 함께 담는다")
	void 실패_목록은_스텝별_현황과_집계를_함께_담는다() {
		// given 장르가 상한을 소진한 보강 하나
		exhaustGenre();

		// when
		EnrichResult.FailedPage page = enrichFacade.findFailed(new EnrichCriteria.FailedSearch(0, 20));

		// then "이번에 막힌 것이 AI 인가 Google 인가"의 답은 전체 집계여야 한다
		assertThat(page.totalCount()).isEqualTo(1);
		assertThat(page.items()).singleElement()
				.satisfies(failed -> {
					assertThat(failed.vendorKey()).isEqualTo(vendorKey);
					assertThat(failed.steps()).hasSize(3);
				});
		assertThat(page.stepCounts())
				.filteredOn(count -> count.step() == EnrichStep.GENRE)
				.singleElement()
				.satisfies(count -> assertThat(count.count()).isEqualTo(1));
	}

	@Test
	@DisplayName("재시도는 실패한 스텝만 다시 열고 시도 횟수를 0으로 되돌린다")
	void 재시도는_실패한_스텝만_다시_연다() {
		// given 장르만 실패하고 상세는 끝난 보강
		exhaustGenre();
		assertThat(statusOf(EnrichStep.DETAIL)).isEqualTo(StepStatus.DONE);

		// when
		EnrichResult.Reopened reopened = enrichFacade.reopen(vendorKey);

		// then 끝난 스텝을 다시 열지 않는다
		assertThat(reopened.reopenedSteps()).containsExactly(EnrichStep.GENRE);
		assertThat(statusOf(EnrichStep.GENRE)).isEqualTo(StepStatus.READY);
		assertThat(statusOf(EnrichStep.DETAIL)).isEqualTo(StepStatus.DONE);
		assertThat(enrichment().attemptsOf(EnrichStep.GENRE)).isZero();
	}

	@Test
	@DisplayName("재시도는 되돌리기와 실행 이벤트 적재를 한 트랜잭션으로 묶는다")
	void 재시도는_되돌리기와_적재를_함께_한다() {
		// given
		exhaustGenre();
		int before = outboxOf(IngestionEventType.GENRE_READY).size();

		// when
		enrichFacade.reopen(vendorKey);

		// then 상태만 열리고 아무도 부르지 않는 상태를 만들지 않는다
		assertThat(outboxOf(IngestionEventType.GENRE_READY)).hasSize(before + 1);
		// 루트를 ENRICHING 으로 되돌리지 않으면 재시도가 성공해도 완료 판정이 영영 참이 되지 않는다
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.ENRICHING);
	}

	@Test
	@DisplayName("실패하지 않은 보강은 재시도를 거절한다")
	void 실패하지_않은_보강은_재시도를_거절한다() {
		// given 진행 중인 보강
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		openThroughDetail();

		// when & then 진행 중인 건을 건드리면 시도 횟수가 초기화된다
		assertThatThrownBy(() -> enrichFacade.reopen(vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(EnrichErrorCode.ENRICHMENT_NOT_FAILED);
	}

	/** 상세는 끝내고 장르만 상한까지 실패시킨다. */
	private void exhaustGenre() {
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.exhausted(
				java.util.List.of(new GenreResult.Attempt(
						modi.backend.domain.exhibition.genre.GenreProvider.GEMINI, "429 한도 초과"))));
		openThroughDetail();
		for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
			assertThatThrownBy(() -> handle(IngestionEventType.GENRE_READY, vendorKey))
					.isInstanceOf(CoreException.class);
		}
	}
}
