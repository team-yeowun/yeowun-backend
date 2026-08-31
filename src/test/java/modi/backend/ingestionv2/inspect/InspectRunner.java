package modi.backend.ingestionv2.inspect;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.ingestionv2.IngestionTestSupport;
import modi.backend.ingestionv2.collect.domain.CatalogItem;
import modi.backend.ingestionv2.collect.domain.CollectCriteria;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceData;
import modi.backend.ingestionv2.inspect.domain.Inspection;
import modi.backend.ingestionv2.inspect.domain.InspectFacade;
import modi.backend.ingestionv2.inspect.infra.InspectionJpaRepository;

/**
 * 회차를 한 번 돌려 원장 세 종을 만든 뒤 점검까지 소비를 끝내는 준비 절차.
 *
 * <p>점검 전용 토대를 새로 만들지 않고 공통 토대를 상속만 한다. 준비 헬퍼가 세 클래스 이상에서
 * 복사되기에 이 자리를 하나 두었고, 그 밖의 준비는 각 테스트 클래스의 private 메서드로 남긴다.
 */
abstract class InspectRunner extends IngestionTestSupport {

	@Autowired protected CollectFacade collectFacade;
	@Autowired protected InspectFacade inspectFacade;
	@Autowired protected InspectionJpaRepository inspectionRepository;

	/** 목록 한 건으로 수집과 보강을 태워 원장 세 종을 만든 뒤, 점검까지 소비를 끝낸다. */
	protected void run(CatalogItem item, String genreKeyword, PlaceData place) {
		given(catalogClient.fetchCatalog()).willReturn(List.of(item));
		given(detailClient.fetchDetail(vendorKey)).willReturn(InspectFixtures.detailData());
		given(genreClassifier.classify(any(), any()))
				.willReturn(GenreResult.classified(genreKeyword, GenreProvider.GEMINI, "gemini-test", List.of()));
		given(placeHoursClient.fetchPlace(any(), any())).willReturn(place);

		collectFacade.collect(CollectCriteria.Batch.of(IngestionClock.today()));
		drainAll();
	}

	/** 정상 값으로 끝까지 태운다. */
	protected void runNormal() {
		run(InspectFixtures.normalItem(vendorKey), "현대미술", InspectFixtures.found());
	}

	protected Inspection inspection() {
		return inspectionRepository.findByVendorKey(vendorKey).orElseThrow();
	}
}
