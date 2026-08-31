package modi.backend.ingestionv2.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.StepStatus;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;

@DisplayName("하위 하나의 실패가 나머지로 번지지 않는다")
class EnrichPartialFailureIntegrationTest extends EnrichTestSupport {

	@Test
	@DisplayName("장르가 실패해도 개장 시간은 정상적으로 끝난다")
	void 장르가_실패해도_개장_시간은_끝난다() {
		// given 상세까지 끝내고 장르만 실패시킨다
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		// 장르는 실패도 결과값으로 돌려받는다. 예외를 던지면 핸들러가 기록 없이 미처리로 남기는 다른 갈래다.
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.exhausted(
				List.of(new GenreResult.Attempt(GenreProvider.GEMINI, "429 한도 초과"))));
		given(placeHoursClient.fetchPlace(any(), any())).willReturn(placeData());
		openThroughDetail();

		// when 장르는 실패하고 개장 시간은 성공한다
		assertThatThrownBy(() -> handle(IngestionEventType.GENRE_READY, vendorKey))
				.isInstanceOf(RuntimeException.class);
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// then 하위 도메인을 나눈 이유가 부분 실패를 격리하는 것이었다
		assertThat(statusOf(EnrichStep.HOURS)).isEqualTo(StepStatus.DONE);
		assertThat(statusOf(EnrichStep.GENRE)).isEqualTo(StepStatus.READY);
		assertThat(placeLedgerRepository.existsByVendorKey(vendorKey)).isTrue();
		assertThat(genreLedgerRepository.existsByVendorKey(vendorKey)).isFalse();
	}

	@Test
	@DisplayName("하위 하나가 남아 있으면 완료 이벤트가 적재되지 않는다")
	void 하위_하나가_남으면_완료가_적재되지_않는다() {
		// given
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		// 장르는 실패도 결과값으로 돌려받는다. 예외를 던지면 핸들러가 기록 없이 미처리로 남기는 다른 갈래다.
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.exhausted(
				List.of(new GenreResult.Attempt(GenreProvider.GEMINI, "429 한도 초과"))));
		given(placeHoursClient.fetchPlace(any(), any())).willReturn(placeData());
		openThroughDetail();

		// when
		assertThatThrownBy(() -> handle(IngestionEventType.GENRE_READY, vendorKey))
				.isInstanceOf(RuntimeException.class);
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// then
		assertThat(outboxOf(IngestionEventType.ENRICHED)).isEmpty();
	}

	@Test
	@DisplayName("실패한 스텝만 시도 횟수가 늘고 나머지는 0이다")
	void 실패한_스텝만_시도_횟수가_는다() {
		// given
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		// 장르는 실패도 결과값으로 돌려받는다. 예외를 던지면 핸들러가 기록 없이 미처리로 남기는 다른 갈래다.
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.exhausted(
				List.of(new GenreResult.Attempt(GenreProvider.GEMINI, "429 한도 초과"))));
		given(placeHoursClient.fetchPlace(any(), any())).willReturn(placeData());
		openThroughDetail();

		// when
		assertThatThrownBy(() -> handle(IngestionEventType.GENRE_READY, vendorKey))
				.isInstanceOf(RuntimeException.class);
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// then 관리자 화면이 "장르만 세 번 실패했다"를 도메인에서 조회할 수 있다
		assertThat(enrichment().attemptsOf(EnrichStep.GENRE)).isEqualTo(1);
		assertThat(enrichment().attemptsOf(EnrichStep.HOURS)).isZero();
		assertThat(enrichment().attemptsOf(EnrichStep.DETAIL)).isZero();
		assertThat(enrichment().lastErrorOf(EnrichStep.GENRE)).isNotBlank();
	}
}
