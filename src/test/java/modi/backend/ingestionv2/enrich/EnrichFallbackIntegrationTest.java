package modi.backend.ingestionv2.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;

@DisplayName("성공 사실과 실패 사실이 서로 다른 곳에 남는다")
class EnrichFallbackIntegrationTest extends EnrichTestSupport {

	@Test
	@DisplayName("폴백으로 성공하면 원장에는 성공한 공급자가 남는다")
	void 폴백으로_성공하면_원장에는_성공한_공급자가_남는다() {
		// given 1차가 실패하고 2차가 답했다
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.classified(
				"현대미술", GenreProvider.OPENAI, "gpt-5.4-nano",
				List.of(new GenreResult.Attempt(GenreProvider.GEMINI, "429 한도 초과"))));
		openThroughDetail();

		// when
		handle(IngestionEventType.GENRE_READY, vendorKey);

		// then 원장은 성공한 사실만 담는다
		assertThat(genreLedgerRepository.findByVendorKey(vendorKey).orElseThrow().getVendor())
				.isEqualTo(GenreProvider.OPENAI);
	}

	@Test
	@DisplayName("폴백까지 갔다는 사실은 하위 진행에 남는다")
	void 폴백까지_갔다는_사실은_하위_진행에_남는다() {
		// given
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.classified(
				"현대미술", GenreProvider.OPENAI, "gpt-5.4-nano",
				List.of(new GenreResult.Attempt(GenreProvider.GEMINI, "429 한도 초과"))));
		openThroughDetail();

		// when
		handle(IngestionEventType.GENRE_READY, vendorKey);

		// then 1차 공급자의 한도 소진이 어디에도 드러나지 않으면 폴백 증가 신호를 놓친다
		assertThat(enrichment().isGenreFallbackUsed()).isTrue();
		assertThat(enrichment().lastAttemptVendorOf(EnrichStep.GENRE)).isEqualTo(GenreProvider.OPENAI.name());
	}

	@Test
	@DisplayName("전 공급자가 소진되면 원장은 비고 실패한 시도만 하위에 남는다")
	void 전_공급자_소진이면_원장은_비고_시도만_남는다() {
		// given 전 공급자 소진(포트가 예외가 아니라 결과값으로 돌려준다)
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.exhausted(List.of(
				new GenreResult.Attempt(GenreProvider.GEMINI, "429 한도 초과"),
				new GenreResult.Attempt(GenreProvider.OPENAI, "타임아웃"))));
		openThroughDetail();

		// when
		assertThatThrownBy(() -> handle(IngestionEventType.GENRE_READY, vendorKey))
				.isInstanceOf(RuntimeException.class);

		// then 분류 실패는 원장 행을 만들지 않는다("원장이 있으면 그 스텝은 끝났다"는 불변식)
		assertThat(genreLedgerRepository.existsByVendorKey(vendorKey)).isFalse();
		assertThat(enrichment().isGenreFallbackUsed()).isTrue();
		assertThat(enrichment().lastAttemptVendorOf(EnrichStep.GENRE)).isEqualTo(GenreProvider.OPENAI.name());
		assertThat(enrichment().lastErrorOf(EnrichStep.GENRE)).contains("GEMINI").contains("OPENAI");
	}
}
