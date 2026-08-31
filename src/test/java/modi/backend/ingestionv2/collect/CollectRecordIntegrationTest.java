package modi.backend.ingestionv2.collect;

import static modi.backend.ingestionv2.collect.CollectFixtures.catalogItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.collect.domain.CollectCriteria;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.collect.domain.CollectResult;
import modi.backend.ingestionv2.collect.domain.CollectedExhibition;
import modi.backend.ingestionv2.collect.domain.CollectionStatus;
import modi.backend.ingestionv2.collect.domain.CultureListSnapshot;
import modi.backend.ingestionv2.collect.infra.CollectedExhibitionJpaRepository;
import modi.backend.ingestionv2.collect.infra.ListLedgerJpaRepository;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.outbox.Outbox;

@DisplayName("전시 한 건의 반영")
class CollectRecordIntegrationTest extends IngestionTestSupport {

	private static final LocalDate BATCH_DATE = LocalDate.of(2026, 8, 25);

	@Autowired private CollectFacade collectFacade;
	@Autowired private CollectedExhibitionJpaRepository collectedRepository;
	@Autowired private ListLedgerJpaRepository snapshotRepository;

	@Test
	@DisplayName("전시 한 건이 실패해도 같은 회차의 나머지는 확정된다")
	void 전시_한_건이_실패해도_나머지가_확정된다() {
		// given 가운데 한 건만 vendor_key 컬럼 길이(100)를 넘겨 그 건의 트랜잭션만 실패시킨다
		String brokenKey = "X".repeat(200);
		given(catalogClient.fetchCatalog()).willReturn(List.of(
				catalogItem(vendorKey + "-1"),
				catalogItem(brokenKey),
				catalogItem(vendorKey + "-2")));

		// when
		CollectResult.Batch result = collectFacade.collect(CollectCriteria.Batch.of(BATCH_DATE));

		// then 실패는 그 한 건에 머물고 앞뒤는 커밋되어 남는다
		assertThat(result.collected()).isEqualTo(2);
		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.skipped()).isZero();
		assertThat(collectedRepository.existsByVendorKey(vendorKey + "-1")).isTrue();
		assertThat(collectedRepository.existsByVendorKey(vendorKey + "-2")).isTrue();
	}

	@Test
	@DisplayName("실패한 전시는 행과 원장과 이벤트를 모두 남기지 않는다")
	void 실패한_전시는_행과_원장과_이벤트를_모두_남기지_않는다() {
		// given
		String brokenKey = "X".repeat(200);
		given(catalogClient.fetchCatalog()).willReturn(List.of(
				catalogItem(vendorKey + "-1"),
				catalogItem(brokenKey),
				catalogItem(vendorKey + "-2")));

		// when
		collectFacade.collect(CollectCriteria.Batch.of(BATCH_DATE));

		// then 세 수가 일치해야 원장 없는 확정이나 이벤트 없는 확정이 없다는 뜻이다
		assertThat(collectedRepository.count()).isEqualTo(2);
		assertThat(snapshotRepository.count()).isEqualTo(2);
		assertThat(outboxRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("확정된 전시는 상태가 COLLECTED 로 저장된다")
	void 확정된_전시는_상태가_COLLECTED_로_저장된다() {
		// given
		given(catalogClient.fetchCatalog()).willReturn(List.of(catalogItem(vendorKey)));

		// when
		collectFacade.collect(CollectCriteria.Batch.of(BATCH_DATE));

		// then
		CollectedExhibition collected = collectedRepository.findAll().getFirst();
		assertThat(collected.getStatus()).isEqualTo(CollectionStatus.COLLECTED);
		assertThat(collected.getBatchDate()).isEqualTo(BATCH_DATE);
	}

	@Test
	@DisplayName("확정된 전시는 목록 원장을 함께 남긴다")
	void 확정된_전시는_목록_원장을_함께_남긴다() {
		// given
		given(catalogClient.fetchCatalog()).willReturn(List.of(catalogItem(vendorKey)));

		// when
		collectFacade.collect(CollectCriteria.Batch.of(BATCH_DATE));

		// then 이벤트에 원천 키만 담은 판단이 원장의 존재에 전적으로 기댄다
		CultureListSnapshot snapshot = snapshotRepository.findByVendorKey(vendorKey).orElseThrow();
		assertThat(snapshot.getTitle()).isEqualTo("여운 기획전");
		assertThat(snapshot.getStartDate()).isEqualTo("2026-08-01");
		assertThat(snapshot.getEndDate()).isEqualTo("2026-12-31");
	}

	@Test
	@DisplayName("COLLECTED 가 확정 건수만큼 원천 키만 담고 쌓인다")
	void COLLECTED_가_확정_건수만큼_원천_키만_담고_쌓인다() {
		// given
		given(catalogClient.fetchCatalog()).willReturn(List.of(
				catalogItem(vendorKey + "-1"),
				catalogItem(vendorKey + "-2"),
				catalogItem(vendorKey + "-3")));

		// when
		collectFacade.collect(CollectCriteria.Batch.of(BATCH_DATE));

		// then 적재가 확정 트랜잭션에 합류한다는 주장의 직접 확인
		List<Outbox> rows = outboxRepository.findAll();
		assertThat(rows).hasSize(3);
		assertThat(rows).allMatch(outbox -> outbox.getEventType() == IngestionEventType.COLLECTED);
		assertThat(rows).extracting(Outbox::getAggregateId)
				.containsExactlyInAnyOrder(vendorKey + "-1", vendorKey + "-2", vendorKey + "-3");
	}
}
