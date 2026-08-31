package modi.backend.ingestionv2.collect;

import static modi.backend.ingestionv2.collect.CollectFixtures.catalogItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.collect.domain.CatalogItem;
import modi.backend.ingestionv2.collect.domain.CollectCriteria;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.collect.domain.CollectResult;
import modi.backend.ingestionv2.collect.domain.CollectedExhibition;
import modi.backend.ingestionv2.collect.infra.CollectedExhibitionJpaRepository;
import modi.backend.ingestionv2.collect.infra.ListLedgerJpaRepository;

@DisplayName("두 겹 멱등")
class CollectIdempotencyIntegrationTest extends IngestionTestSupport {

	private static final LocalDate BATCH_DATE = LocalDate.of(2026, 8, 25);

	@Autowired private CollectFacade collectFacade;
	@Autowired private CollectedExhibitionJpaRepository collectedRepository;
	@Autowired private ListLedgerJpaRepository snapshotRepository;

	@Test
	@DisplayName("같은 회차를 다시 실행하면 전부 건너뛴다")
	void 같은_회차를_다시_실행하면_전부_건너뛴다() {
		// given 회차를 한 번 실행한 뒤 마크만 지운다(관리자가 회차를 되돌린 상황)
		given(catalogClient.fetchCatalog()).willReturn(List.of(
				catalogItem(vendorKey + "-1"),
				catalogItem(vendorKey + "-2"),
				catalogItem(vendorKey + "-3")));
		collectFacade.collect(CollectCriteria.Batch.of(BATCH_DATE));
		jdbcTemplate.execute("delete from ingestion_collect_batch_mark");

		// when 같은 회차·같은 목록으로 재실행한다
		CollectResult.Batch result = collectFacade.collect(CollectCriteria.Batch.of(BATCH_DATE));

		// then 조회가 정상 경로를 예외 없이 건너뛴다
		assertThat(result.collected()).isZero();
		assertThat(result.skipped()).isEqualTo(3);
		assertThat(collectedRepository.count()).isEqualTo(3);
		assertThat(outboxRepository.count()).isEqualTo(3);
	}

	@Test
	@DisplayName("조회를 우회해도 vendor_key 유일 제약이 중복 확정을 막는다")
	void 조회를_우회해도_유일_제약이_중복_확정을_막는다() {
		// given 같은 원천 키의 애그리거트 두 개
		CatalogItem item = catalogItem(vendorKey);
		collectedRepository.saveAndFlush(CollectedExhibition.create(item, BATCH_DATE));

		// when 조회를 거치지 않고 같은 키로 한 번 더 저장한다
		// then 데이터베이스가 거부한다(선점 로직에 결함이 생겼을 때의 마지막 방어선)
		assertThatThrownBy(() -> collectedRepository.saveAndFlush(CollectedExhibition.create(item, BATCH_DATE)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(collectedRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("다른 회차가 같은 전시를 다시 발견하면 첫 확정을 유지한다")
	void 다른_회차가_같은_전시를_다시_발견하면_첫_확정을_유지한다() {
		// given 어제 회차로 확정
		LocalDate yesterday = BATCH_DATE.minusDays(1);
		given(catalogClient.fetchCatalog()).willReturn(List.of(catalogItem(vendorKey)));
		collectFacade.collect(CollectCriteria.Batch.of(yesterday));
		var firstObservedAt = snapshotRepository.findByVendorKey(vendorKey).orElseThrow().getObservedAt();

		// when 오늘 회차에 같은 원천 키가 목록에 다시 등장한다
		CollectResult.Batch result = collectFacade.collect(CollectCriteria.Batch.of(BATCH_DATE));

		// then 원장의 첫 관측이 덮이지 않아 뒤 격벽의 읽기 일관성이 유지된다
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(collectedRepository.findAll().getFirst().getBatchDate()).isEqualTo(yesterday);
		assertThat(snapshotRepository.findByVendorKey(vendorKey).orElseThrow().getObservedAt())
				.isEqualTo(firstObservedAt);
	}
}
