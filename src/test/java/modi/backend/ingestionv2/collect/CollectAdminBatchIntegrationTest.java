package modi.backend.ingestionv2.collect;

import static modi.backend.ingestionv2.collect.CollectFixtures.catalogItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.collect.domain.CollectCriteria;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.collect.domain.CollectResult;
import modi.backend.ingestionv2.collect.infra.CollectedExhibitionJpaRepository;

@DisplayName("관리자 지정 실행")
class CollectAdminBatchIntegrationTest extends IngestionTestSupport {

	@Autowired private CollectFacade collectFacade;
	@Autowired private CollectedExhibitionJpaRepository collectedRepository;

	@Test
	@DisplayName("관리자가 지정한 밀린 회차를 실행하면 그 회차로 확정된다")
	void 관리자가_지정한_밀린_회차를_실행하면_그_회차로_확정된다() {
		// given 3일 전 날짜와 목록 2건
		LocalDate threeDaysAgo = LocalDate.now().minusDays(3);
		given(catalogClient.fetchCatalog()).willReturn(List.of(
				catalogItem(vendorKey + "-1"),
				catalogItem(vendorKey + "-2")));

		// when
		CollectResult.Batch result = collectFacade.collect(CollectCriteria.Batch.of(threeDaysAgo));

		// then 파사드가 회차 날짜를 인자로 받는 시그니처 덕에 회복 경로가 열려 있다
		assertThat(result.claimed()).isTrue();
		assertThat(collectedRepository.findAll())
				.hasSize(2)
				.allMatch(collected -> collected.getBatchDate().equals(threeDaysAgo));
	}

	@Test
	@DisplayName("이미 선점된 회차를 관리자가 지정하면 아무 일도 하지 않는다")
	void 이미_선점된_회차를_관리자가_지정하면_아무_일도_하지_않는다() {
		// given 그 회차를 한 번 실행해 둔다
		LocalDate batchDate = LocalDate.now().minusDays(3);
		AtomicInteger fetchCount = new AtomicInteger();
		given(catalogClient.fetchCatalog()).willAnswer(invocation -> {
			fetchCount.incrementAndGet();
			return List.of(catalogItem(vendorKey));
		});
		collectFacade.collect(CollectCriteria.Batch.of(batchDate));
		int afterFirst = fetchCount.get();
		long rowsAfterFirst = collectedRepository.count();

		// when 같은 회차를 관리자 경로로 재지정한다
		CollectResult.Batch result = collectFacade.collect(CollectCriteria.Batch.of(batchDate));

		// then 회차당 1회라는 규칙이 관리자 경로로 뚫리지 않는다
		assertThat(result.claimed()).isFalse();
		assertThat(fetchCount.get()).isEqualTo(afterFirst);
		assertThat(collectedRepository.count()).isEqualTo(rowsAfterFirst);
	}
}
