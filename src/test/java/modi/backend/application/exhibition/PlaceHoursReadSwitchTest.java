package modi.backend.application.exhibition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.infra.exhibition.hours.PlaceHoursJpaRepository;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;

/**
 * 상세 operatingHours <b>읽기 출처</b> 검증(@SpringBootTest + Testcontainers-MySQL).
 * <p>
 * 이관 후 영업시간은 전시장(exhibition_place)에 정렬된 정준층(place_hours)에서 온다 — exhibitions에는 영업시간 컬럼이 없다.
 * 정준행이 없으면 operatingHours는 null(폴백 없음), 같은 전시장을 공유하는 전시들은 한 정준행을 함께 읽는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class PlaceHoursReadSwitchTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionFacade exhibitionFacade;

	@Autowired
	ExhibitionRepository exhibitionRepository;

	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Autowired
	PlaceHoursJpaRepository placeHoursRepository;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("상세 operatingHours는 전시장 정준층(place_hours)에서 온다")
	void 상세_operatingHours_정준층에서_읽는다() {
		ExhibitionPlace place = seedPlace();
		Exhibition e = seedCatalog(place);
		placeHoursRepository.save(PlaceHours.first(place.getId(), "매일 10:00 ~ 18:00\n월 휴무",
				PlaceHoursStatus.SUCCEEDED, PlaceHoursVendor.GOOGLE, LocalDateTime.now()));

		ExhibitionResult.Detail detail = exhibitionFacade.getDetail(new ExhibitionCriteria.Detail(e.getId(), null));

		assertThat(detail.operatingHours()).isEqualTo("매일 10:00 ~ 18:00\n월 휴무");
	}

	@Test
	@DisplayName("정준층 행이 없으면 operatingHours는 null(폴백 없음)")
	void 정준층_행없음_null() {
		Exhibition e = seedCatalog(seedPlace());

		ExhibitionResult.Detail detail = exhibitionFacade.getDetail(new ExhibitionCriteria.Detail(e.getId(), null));

		assertThat(detail.operatingHours()).isNull();
	}

	@Test
	@DisplayName("같은 전시장의 전시들은 정준층 한 행을 공유한다(장소당 1행 — 전시 수와 무관)")
	void 같은장소_전시들이_한행을_공유한다() {
		ExhibitionPlace place = seedPlace();
		Exhibition a = seedCatalog(place);
		Exhibition b = seedCatalog(place);
		placeHoursRepository.save(PlaceHours.first(place.getId(), "매일 11:00 ~ 19:00",
				PlaceHoursStatus.SUCCEEDED, PlaceHoursVendor.GOOGLE, LocalDateTime.now()));

		assertThat(detailOf(a).operatingHours()).isEqualTo("매일 11:00 ~ 19:00");
		assertThat(detailOf(b).operatingHours()).isEqualTo("매일 11:00 ~ 19:00");
	}

	@Test
	@DisplayName("스냅샷 조회(getForSnapshot)의 operatingHours도 같은 출처를 본다")
	void 스냅샷_operatingHours_정준층에서_읽는다() {
		ExhibitionPlace place = seedPlace();
		Exhibition e = seedCatalog(place);
		placeHoursRepository.save(PlaceHours.first(place.getId(), "정준 값",
				PlaceHoursStatus.SUCCEEDED, PlaceHoursVendor.UNKNOWN, LocalDateTime.now()));

		ExhibitionResult.Detail detail = exhibitionFacade.getForSnapshot(e.getId(), null);

		assertThat(detail.operatingHours()).isEqualTo("정준 값");
	}

	// ── 헬퍼 ────────────────────────────────────────────────────────────────────

	private ExhibitionResult.Detail detailOf(Exhibition e) {
		return exhibitionFacade.getDetail(new ExhibitionCriteria.Detail(e.getId(), null));
	}

	private ExhibitionPlace seedPlace() {
		return exhibitionPlaceRepository.save(ExhibitionPlace.createFromList(
				"영업시간 읽기 전환 시립미술관 " + SEQ.getAndIncrement(), ExhibitionRegion.SEOUL, null, null, null));
	}

	private Exhibition seedCatalog(ExhibitionPlace place) {
		return exhibitionRepository.save(Exhibition.createCatalog("HOURS-READ-" + SEQ.getAndIncrement(),
				"영업시간 읽기 전환 전시", place.getId(), place.getRegion(), null, null, ExhibitionCategory.PAINTING, null, null, "기관"));
	}
}
