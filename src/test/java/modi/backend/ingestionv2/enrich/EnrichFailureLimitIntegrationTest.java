package modi.backend.ingestionv2.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichErrorCode;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStatus;
import modi.backend.ingestionv2.enrich.domain.StepStatus;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;
import modi.backend.support.error.CoreException;

@DisplayName("상한과 즉시 확정과 종결 가드가 각자의 신호를 낸다")
class EnrichFailureLimitIntegrationTest extends EnrichTestSupport {

	@Test
	@DisplayName("상한 미만의 실패는 원래 예외를 그대로 되돌린다")
	void 상한_미만의_실패는_원래_예외를_되돌린다() {
		// given
		given(detailClient.fetchDetail(vendorKey))
				.willThrow(new CoreException(EnrichErrorCode.DETAIL_FETCH_FAILED, "원천 장애"));
		handle(IngestionEventType.COLLECTED, vendorKey);

		// when & then 배달 계층은 이 항목을 미확인으로 두고 나중에 다시 전달한다
		assertThatThrownBy(() -> handle(IngestionEventType.DETAIL_READY, vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(EnrichErrorCode.DETAIL_FETCH_FAILED);
		assertThat(enrichment().attemptsOf(EnrichStep.DETAIL)).isEqualTo(1);
		assertThat(statusOf(EnrichStep.DETAIL)).isEqualTo(StepStatus.READY);
	}

	@Test
	@DisplayName("상한을 소진하면 RETRY_EXHAUSTED 로 바꿔 던진다")
	void 상한을_소진하면_RETRY_EXHAUSTED로_바꿔_던진다() {
		// given 상한(3)까지 실패시킨다
		given(detailClient.fetchDetail(vendorKey))
				.willThrow(new CoreException(EnrichErrorCode.DETAIL_FETCH_FAILED, "원천 장애"));
		handle(IngestionEventType.COLLECTED, vendorKey);
		for (int attempt = 1; attempt < properties.maxAttempts(); attempt++) {
			assertThatThrownBy(() -> handle(IngestionEventType.DETAIL_READY, vendorKey))
					.isInstanceOf(CoreException.class);
		}

		// when 마지막 시도
		// then 판정은 도메인이 하고 실행(격리)은 배달 계층이 한다
		assertThatThrownBy(() -> handle(IngestionEventType.DETAIL_READY, vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(IngestionErrorCode.RETRY_EXHAUSTED);
	}

	@Test
	@DisplayName("상한을 소진하면 그 스텝과 루트가 FAILED 로 확정된다")
	void 상한을_소진하면_스텝과_루트가_FAILED가_된다() {
		// given
		exhaustDetail();

		// then
		assertThat(statusOf(EnrichStep.DETAIL)).isEqualTo(StepStatus.FAILED);
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.FAILED);
		assertThat(enrichment().attemptsOf(EnrichStep.DETAIL)).isEqualTo(properties.maxAttempts());
	}

	@Test
	@DisplayName("담당 스텝이 없는 수신구의 실패는 곧바로 상한 소진으로 취급한다")
	void 담당_스텝이_없는_실패는_곧바로_상한_소진이다() {
		// given 시도 횟수를 적을 하위 엔티티가 아직 없는 상태에서 실패한다
		jdbcTemplate.execute("drop table if exists ingestion_enrichment_tmp_missing");

		// when & then 세는 자리가 없는 상한은 실제로는 무한 재시도라 보이는 격리를 고른다
		assertThatThrownBy(() -> handle(IngestionEventType.COLLECTED, "X".repeat(200)))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(IngestionErrorCode.RETRY_EXHAUSTED);
	}

	@Test
	@DisplayName("분류할 재료가 없으면 재시도 없이 즉시 실패로 확정한다")
	void 분류할_재료가_없으면_즉시_확정한다() {
		// given 원천에서 전시가 사라져 상세가 absent 로 기록된다
		given(detailClient.fetchDetail(vendorKey))
				.willReturn(modi.backend.ingestionv2.enrich.domain.detail.DetailData.none());
		openThroughDetail();

		// when
		handle(IngestionEventType.GENRE_READY, vendorKey);

		// then 세 번 더 시도해도 결과가 달라지지 않는 실패는 재시도 대상이 아니다
		assertThat(statusOf(EnrichStep.GENRE)).isEqualTo(StepStatus.FAILED);
		assertThat(enrichment().attemptsOf(EnrichStep.GENRE)).isZero();
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.FAILED);
	}

	@Test
	@DisplayName("조회할 장소 표기가 없으면 재시도 없이 즉시 실패로 확정한다")
	void 조회할_장소_표기가_없으면_즉시_확정한다() {
		// given
		given(detailClient.fetchDetail(vendorKey))
				.willReturn(modi.backend.ingestionv2.enrich.domain.detail.DetailData.none());
		openThroughDetail();

		// when
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// then 유료 호출 세 번과 격리 항목 하나를 아낀다
		assertThat(statusOf(EnrichStep.HOURS)).isEqualTo(StepStatus.FAILED);
		assertThat(enrichment().attemptsOf(EnrichStep.HOURS)).isZero();
	}

	@Test
	@DisplayName("입력이 없으면 외부 호출을 하지 않는다")
	void 입력이_없으면_외부_호출을_하지_않는다() {
		// given
		given(detailClient.fetchDetail(vendorKey))
				.willReturn(modi.backend.ingestionv2.enrich.domain.detail.DetailData.none());
		openThroughDetail();

		// when
		handle(IngestionEventType.GENRE_READY, vendorKey);
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// then 원장이 없으므로 호출 자체가 일어나지 않았다
		assertThat(genreLedgerRepository.count()).isZero();
		assertThat(placeLedgerRepository.count()).isZero();
	}

	@Test
	@DisplayName("이미 끝난 스텝에 늦게 도착한 실패는 종결을 뒤집지 않는다")
	void 이미_끝난_스텝에_늦은_실패는_종결을_뒤집지_않는다() {
		// given 세 스텝이 모두 끝나 완료된 보강
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willReturn(genreResult());
		given(placeHoursClient.fetchPlace(any(), any())).willReturn(placeData());
		openThroughDetail();
		handle(IngestionEventType.GENRE_READY, vendorKey);
		handle(IngestionEventType.HOURS_READY, vendorKey);
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.COMPLETED);

		// when 정체되었다 회수된 실행이 뒤늦게 깨어나 실패를 기록한다
		var outcome = enrichFacade.recordFailure(EnrichStep.GENRE, vendorKey, "GEMINI", "뒤늦은 타임아웃");

		// then 데이터는 멀쩡한데 상태만 거짓이 되는 뒤집힘을 막는다
		assertThat(outcome).isEqualTo(modi.backend.ingestionv2.enrich.domain.FailureOutcome.ALREADY_DONE);
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.COMPLETED);
		assertThat(enrichment().attemptsOf(EnrichStep.GENRE)).isZero();
	}

	@Test
	@DisplayName("이미 끝난 스텝의 늦은 실패는 핸들러가 예외 없이 정상 반환한다")
	void 이미_끝난_스텝의_늦은_실패는_예외_없이_끝난다() {
		// given 장르는 끝났고 분류기는 여전히 실패를 돌려준다
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willReturn(genreResult());
		openThroughDetail();
		handle(IngestionEventType.GENRE_READY, vendorKey);
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.exhausted(
				List.of(new GenreResult.Attempt(GenreProvider.GEMINI, "429"))));

		// when & then 여기서 예외를 던지면 끝난 항목이 회수 주기마다 되살아난다
		assertThatCode(() -> handle(IngestionEventType.GENRE_READY, vendorKey)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("장르 소진은 폴백 사실과 실패한 공급자 목록을 함께 남긴다")
	void 장르_소진은_폴백_사실을_함께_남긴다() {
		// given
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willReturn(GenreResult.exhausted(List.of(
				new GenreResult.Attempt(GenreProvider.GEMINI, "429"),
				new GenreResult.Attempt(GenreProvider.OPENAI, "timeout"))));
		openThroughDetail();

		// when 상한까지 실패시킨다
		for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
			assertThatThrownBy(() -> handle(IngestionEventType.GENRE_READY, vendorKey))
					.isInstanceOf(CoreException.class);
		}

		// then
		assertThat(statusOf(EnrichStep.GENRE)).isEqualTo(StepStatus.FAILED);
		assertThat(enrichment().isGenreFallbackUsed()).isTrue();
		assertThat(enrichment().lastErrorOf(EnrichStep.GENRE)).contains("GEMINI").contains("OPENAI");
	}

	@Test
	@DisplayName("상한 값은 설정이 진실이라 엔티티가 상수로 갖지 않는다")
	void 상한_값은_설정이_진실이다() {
		// given 설정이 3이면 세 번째 시도에서 소진된다
		assertThat(properties.maxAttempts()).isEqualTo(3);

		// when
		exhaustDetail();

		// then
		assertThat(enrichment().attemptsOf(EnrichStep.DETAIL)).isEqualTo(properties.maxAttempts());
	}

	@Test
	@DisplayName("상한 소진 이후에도 실패 기록은 상태를 더 바꾸지 않는다")
	void 상한_소진_이후의_실패_기록은_상태를_더_바꾸지_않는다() {
		// given
		exhaustDetail();
		int attemptsAfterExhaust = enrichment().attemptsOf(EnrichStep.DETAIL);

		// when 한 번 더 실패한다
		assertThatThrownBy(() -> handle(IngestionEventType.DETAIL_READY, vendorKey))
				.isInstanceOf(CoreException.class);

		// then 상태는 FAILED 로 유지되고 시도 횟수만 는다(끝난 스텝이 아니라 실패한 스텝이므로 기록은 계속된다)
		assertThat(statusOf(EnrichStep.DETAIL)).isEqualTo(StepStatus.FAILED);
		assertThat(enrichment().attemptsOf(EnrichStep.DETAIL)).isGreaterThan(attemptsAfterExhaust - 1);
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.FAILED);
	}

	/** 상세 스텝을 상한까지 실패시킨다. */
	private void exhaustDetail() {
		given(detailClient.fetchDetail(vendorKey))
				.willThrow(new CoreException(EnrichErrorCode.DETAIL_FETCH_FAILED, "원천 장애"));
		handle(IngestionEventType.COLLECTED, vendorKey);
		for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
			assertThatThrownBy(() -> handle(IngestionEventType.DETAIL_READY, vendorKey))
					.isInstanceOf(CoreException.class);
		}
	}
}
