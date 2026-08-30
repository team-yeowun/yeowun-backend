package modi.backend.ingestionv2.inspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.support.error.CoreException;

@DisplayName("원장 결손")
class InspectLedgerMissingIntegrationTest extends InspectRunner {

	@Test
	@DisplayName("원장이 결손되면 반려가 아니라 격리 신호가 올라간다")
	void 원장이_결손되면_격리_신호가_올라간다() {
		// given 원장이 하나도 없는 원천 키로 ENRICHED 가 도착한다(선행 격벽의 불변식이 깨진 상황)
		// when & then 결손이 조용한 반려로 바뀌면 시스템 결함이 데이터 판단으로 위장된다
		assertThatThrownBy(() -> handle(IngestionEventType.ENRICHED, vendorKey))
				.isInstanceOf(CoreException.class)
				.extracting(failure -> ((CoreException) failure).errorCode())
				.isEqualTo(IngestionErrorCode.RETRY_EXHAUSTED);
	}

	@Test
	@DisplayName("원장이 결손된 건이 격리 테이블에 도달한다")
	void 원장이_결손된_건이_격리_테이블에_도달한다() {
		// given 결손 상태에서 사실만 적재한다
		outboxAppender.append(IngestionEventType.ENRICHED, vendorKey);

		// when 발송과 소비를 실제로 민다
		drainAll();

		// then 신호를 던지는 것과 격리되는 것은 다른 사실이다
		assertThat(deadLetterRepository.findAll())
				.filteredOn(letter -> vendorKey.equals(letter.getAggregateId()))
				.hasSize(1);
		assertThat(pendingOf(IngestionStream.DB).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("원장이 결손되면 점검 행이 남지 않는다")
	void 원장이_결손되면_점검_행이_남지_않는다() {
		// given
		outboxAppender.append(IngestionEventType.ENRICHED, vendorKey);

		// when
		drainAll();

		// then 점검은 실패해도 애그리거트 행이 생기지 않는다(상태 어휘가 두 값뿐인 결정의 결과)
		assertThat(inspectionRepository.findByVendorKey(vendorKey)).isEmpty();
	}
}
