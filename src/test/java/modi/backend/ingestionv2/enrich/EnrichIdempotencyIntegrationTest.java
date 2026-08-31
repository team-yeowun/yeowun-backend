package modi.backend.ingestionv2.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.enrich.domain.EnrichStep;
import modi.backend.ingestionv2.enrich.domain.StepStatus;

@DisplayName("같은 사실이 두 번 도착해도 유료 호출과 행이 늘지 않는다")
class EnrichIdempotencyIntegrationTest extends EnrichTestSupport {

	@Test
	@DisplayName("수집 완료가 두 번 도착해도 애그리거트가 하나뿐이다")
	void 수집_완료가_두_번_도착해도_애그리거트가_하나다() {
		// when
		handle(IngestionEventType.COLLECTED, vendorKey);
		handle(IngestionEventType.COLLECTED, vendorKey);

		// then 상세 요청도 한 번만 적재된다
		assertThat(enrichmentRepository.count()).isEqualTo(1);
		assertThat(outboxOf(IngestionEventType.DETAIL_READY)).hasSize(1);
	}

	@Test
	@DisplayName("상세 이벤트가 두 번 도착해도 유료 호출은 한 번이다")
	void 상세_이벤트가_두_번_도착해도_호출은_한_번이다() {
		// given 호출 횟수를 스텁이 직접 센다
		AtomicInteger calls = new AtomicInteger();
		given(detailClient.fetchDetail(vendorKey)).willAnswer(invocation -> {
			calls.incrementAndGet();
			return detailData();
		});
		handle(IngestionEventType.COLLECTED, vendorKey);

		// when
		handle(IngestionEventType.DETAIL_READY, vendorKey);
		handle(IngestionEventType.DETAIL_READY, vendorKey);

		// then 판정 단계의 원장 존재 확인이 유료 호출을 아낀다
		assertThat(calls.get()).isEqualTo(1);
		assertThat(detailLedgerRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("상세 이벤트가 두 번 도착해도 후속 이벤트가 두 배로 늘지 않는다")
	void 상세_이벤트가_두_번_도착해도_후속이_늘지_않는다() {
		// given
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		handle(IngestionEventType.COLLECTED, vendorKey);

		// when
		handle(IngestionEventType.DETAIL_READY, vendorKey);
		handle(IngestionEventType.DETAIL_READY, vendorKey);

		// then 멱등이 조건문이 아니라 루트의 반환값(빈 목록)으로 표현되어 있다
		assertThat(outboxOf(IngestionEventType.GENRE_READY)).hasSize(1);
		assertThat(outboxOf(IngestionEventType.HOURS_READY)).hasSize(1);
	}

	@Test
	@DisplayName("장르 이벤트가 두 번 도착해도 원장이 하나뿐이고 호출도 한 번이다")
	void 장르_이벤트가_두_번_도착해도_원장이_하나다() {
		// given
		AtomicInteger calls = new AtomicInteger();
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willAnswer(invocation -> {
			calls.incrementAndGet();
			return genreResult();
		});
		openThroughDetail();

		// when
		handle(IngestionEventType.GENRE_READY, vendorKey);
		handle(IngestionEventType.GENRE_READY, vendorKey);

		// then
		assertThat(calls.get()).isEqualTo(1);
		assertThat(genreLedgerRepository.count()).isEqualTo(1);
		assertThat(statusOf(EnrichStep.GENRE)).isEqualTo(StepStatus.DONE);
	}

	@Test
	@DisplayName("완료 이후 같은 이벤트가 다시 도착해도 완료 이벤트가 두 번 적재되지 않는다")
	void 완료_이후_재전달에도_완료가_두_번_적재되지_않는다() {
		// given 세 스텝이 모두 끝난 상태
		given(detailClient.fetchDetail(vendorKey)).willReturn(detailData());
		given(genreClassifier.classify(any(), any())).willReturn(genreResult());
		given(placeHoursClient.fetchPlace(any(), any())).willReturn(placeData());
		openThroughDetail();
		handle(IngestionEventType.GENRE_READY, vendorKey);
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// when 개장 시간 이벤트가 한 번 더 도착한다
		handle(IngestionEventType.HOURS_READY, vendorKey);

		// then 완료 판정이 ENRICHING 인 경우에만 참을 돌려준다
		assertThat(outboxOf(IngestionEventType.ENRICHED)).hasSize(1);
	}
}
