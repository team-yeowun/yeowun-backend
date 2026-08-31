package modi.backend.ingestionv2.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.EnrichmentStatus;
import modi.backend.ingestionv2.enrich.domain.StepStatus;

@DisplayName("상세가 둘로 갈라지고 마지막 하나가 합류를 판정한다")
class EnrichFanOutIntegrationTest extends EnrichTestSupport {

	@Test
	@DisplayName("수집 완료를 받으면 상세만 열린 보강이 생기고 상세 요청이 적재된다")
	void 수집_완료를_받으면_상세만_열린다() {
		// when
		handle(IngestionEventType.COLLECTED, vendorKey);

		// then 생성과 상세 요청 적재가 한 트랜잭션이라 "행은 있는데 아무도 실행하지 않는" 상태가 없다
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.ENRICHING);
		assertThat(statusOf(EnrichStep.DETAIL)).isEqualTo(StepStatus.READY);
		assertThat(statusOf(EnrichStep.GENRE)).isEqualTo(StepStatus.PENDING);
		assertThat(statusOf(EnrichStep.HOURS)).isEqualTo(StepStatus.PENDING);
		assertThat(outboxOf(IngestionEventType.DETAIL_READY)).hasSize(1);
	}

	@Test
	@DisplayName("상세 반영 한 트랜잭션이 아웃박스에 두 행을 적재한다")
	void 상세_반영이_두_이벤트를_함께_적재한다() {
		// given
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		handle(IngestionEventType.COLLECTED, vendorKey);

		// when
		handle(IngestionEventType.DETAIL_READY, vendorKey);

		// then 브로커의 토픽 팬아웃과 같은 결과를 데이터베이스 쓰기로 만든다
		assertThat(statusOf(EnrichStep.DETAIL)).isEqualTo(StepStatus.DONE);
		assertThat(statusOf(EnrichStep.GENRE)).isEqualTo(StepStatus.READY);
		assertThat(statusOf(EnrichStep.HOURS)).isEqualTo(StepStatus.READY);
		assertThat(outboxOf(IngestionEventType.GENRE_READY)).hasSize(1);
		assertThat(outboxOf(IngestionEventType.HOURS_READY)).hasSize(1);
	}

	@Test
	@DisplayName("상세만 끝난 시점에는 완료 이벤트가 적재되지 않는다")
	void 상세만_끝난_시점에는_완료_이벤트가_없다() {
		// given
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());

		// when
		openThroughDetail();

		// then 서비스가 순서를 미리 계산하지 않고 루트에게 물어 false 를 받는다
		assertThat(outboxOf(IngestionEventType.ENRICHED)).isEmpty();
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.ENRICHING);
	}

	@Test
	@DisplayName("마지막 하위가 끝나는 트랜잭션만 완료 이벤트를 적재한다")
	void 마지막_하위가_끝나는_트랜잭션만_완료를_적재한다() {
		// given 상세까지 끝내고 장르를 먼저 끝낸다
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.willReturn(genreResult());
		given(placeHoursClient.fetchPlace(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.willReturn(placeData());
		openThroughDetail();

		// when 장르 먼저
		handle(IngestionEventType.GENRE_READY, vendorKey);
		assertThat(outboxOf(IngestionEventType.ENRICHED)).isEmpty();

		// when 개장 시간이 마지막으로 끝난다
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// then 완료를 보는 트랜잭션이 정확히 하나다
		assertThat(enrichment().getStatus()).isEqualTo(EnrichmentStatus.COMPLETED);
		assertThat(outboxOf(IngestionEventType.ENRICHED)).hasSize(1);
	}

	@Test
	@DisplayName("세 원장이 모두 남는다")
	void 세_원장이_모두_남는다() {
		// given
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.willReturn(genreResult());
		given(placeHoursClient.fetchPlace(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.willReturn(placeData());

		// when
		openThroughDetail();
		handle(IngestionEventType.GENRE_READY, vendorKey);
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// then 원장이 있으면 진행 상태도 반드시 있다는 불변식이 성립한다
		assertThat(detailLedgerRepository.existsByVendorKey(vendorKey)).isTrue();
		assertThat(genreLedgerRepository.existsByVendorKey(vendorKey)).isTrue();
		assertThat(placeLedgerRepository.existsByVendorKey(vendorKey)).isTrue();
	}
}
