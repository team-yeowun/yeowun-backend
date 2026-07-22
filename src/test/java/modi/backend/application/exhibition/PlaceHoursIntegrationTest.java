package modi.backend.application.exhibition;

import modi.backend.ingestion.application.enricher.PlaceHoursEnricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;
import modi.backend.domain.exhibition.catalog.ExhibitionPlace;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.hours.PlaceHours;
import modi.backend.domain.exhibition.hours.PlaceHoursData;
import modi.backend.ingestion.domain.data.PlaceHoursResult;
import modi.backend.ingestion.domain.port.PlaceHoursProvider;
import modi.backend.infra.exhibition.hours.PlaceHoursJpaRepository;
import modi.backend.domain.exhibition.hours.PlaceHoursStatus;
import modi.backend.domain.exhibition.hours.PlaceHoursVendor;
import modi.backend.domain.exhibition.hours.WeeklyOpeningHours;
import modi.backend.ingestion.infra.GooglePlaceSnapshotJpaRepository;

/**
 * 전시 영업시간 보강 전 경로 통합 검증(@SpringBootTest + Testcontainers-MySQL). 외부 조회기({@link PlaceHoursProvider})만 목으로 두고
 * enricher → 벤더 원본 적재 → 표시 규칙 포맷 → 정준층 저장 을 실제 컴포넌트·DB로 태운다.
 * <p>
 * 이관 3단계에서 조회 단위가 전시장(exhibition_place)이 됐다 — 영업시간은 전시장의 속성이라 place_hours가 exhibition_place_id로
 * 정렬된다. 대상 선별은 "주소 있고 정준행 없거나 만료"이며, 검증은 전시장 id로 정준·벤더 행을 조회해 지킨다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class PlaceHoursIntegrationTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	PlaceHoursEnricher placeHoursEnricher;

	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;

	@Autowired
	GooglePlaceSnapshotJpaRepository googlePlaceSnapshotJpaRepository;

	@Autowired
	PlaceHoursJpaRepository placeHoursRepository;

	@MockitoBean
	PlaceHoursProvider placeHoursProvider;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@org.junit.jupiter.api.BeforeEach
	void stubVendor() {
		given(placeHoursProvider.vendor()).willReturn(PlaceHoursVendor.GOOGLE);
	}

	@Test
	@DisplayName("전시장 단위 1콜로 조회해 정준층 표시값 저장 + 벤더 원본 적재 + 매일 축약")
	void 매일축약_그리고_장소당_1콜() {
		ExhibitionPlace place = seedPlace("부산현대미술관", uniqueAddr());
		given(placeHoursProvider.fetch(eq(place.getName()), eq(place.getAddress())))
				.willReturn(Optional.of(data(place.getAddress(), everyDaySameTimeButMondayClosed())));

		placeHoursEnricher.enrichPlaceHours();

		verify(placeHoursProvider, times(1)).fetch(eq(place.getName()), eq(place.getAddress())); // 장소당 1콜
		assertThat(googlePlaceSnapshotJpaRepository.findByExhibitionPlaceId(place.getId())).isPresent();
		PlaceHours canonical = placeHoursRepository.findByExhibitionPlaceId(place.getId()).orElseThrow();
		assertThat(canonical.getFormatted()).isEqualTo("매일 10:00 ~ 18:00\n월 휴무");
		assertThat(canonical.getStatus()).isEqualTo(PlaceHoursStatus.SUCCEEDED);
		assertThat(canonical.getProvider()).isEqualTo(PlaceHoursVendor.GOOGLE);
		assertThat(canonical.getSyncedAt()).isNotNull();
		assertThat(canonical.getNextAttemptAt()).isNull();
	}

	@Test
	@DisplayName("시간대별 그룹 + 비연속 묶기 + 휴무 맨 아래")
	void 다중그룹_비연속() {
		ExhibitionPlace place = seedPlace("갤러리PH", uniqueAddr());
		WeeklyOpeningHours hours = WeeklyOpeningHours.builder()
				.add(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(18, 0))
				.add(DayOfWeek.WEDNESDAY, LocalTime.of(10, 0), LocalTime.of(18, 0))
				.add(DayOfWeek.THURSDAY, LocalTime.of(13, 0), LocalTime.of(20, 0))
				.add(DayOfWeek.FRIDAY, LocalTime.of(13, 0), LocalTime.of(20, 0))
				.build();
		given(placeHoursProvider.fetch(eq(place.getName()), eq(place.getAddress())))
				.willReturn(Optional.of(data(place.getAddress(), hours)));

		placeHoursEnricher.enrichPlaceHours();

		assertThat(placeHoursRepository.findByExhibitionPlaceId(place.getId()).orElseThrow().getFormatted())
				.isEqualTo("월 / 수 10:00 ~ 18:00\n목 / 금 13:00 ~ 20:00\n화 / 토 / 일 휴무");
	}

	@Test
	@DisplayName("전 요일 영업 동일 시간 → 매일, 휴무 줄 없음")
	void 전영업() {
		ExhibitionPlace place = seedPlace("상시관PH", uniqueAddr());
		WeeklyOpeningHours.Builder builder = WeeklyOpeningHours.builder();
		for (DayOfWeek d : DayOfWeek.values()) {
			builder.add(d, LocalTime.of(9, 0), LocalTime.of(21, 0));
		}
		given(placeHoursProvider.fetch(eq(place.getName()), eq(place.getAddress())))
				.willReturn(Optional.of(data(place.getAddress(), builder.build())));

		placeHoursEnricher.enrichPlaceHours();

		assertThat(placeHoursRepository.findByExhibitionPlaceId(place.getId()).orElseThrow().getFormatted())
				.isEqualTo("매일 09:00 ~ 21:00");
	}

	@Test
	@DisplayName("장소는 찾았으나 영업시간 없음 → formatted null, NO_HOURS, 원본 적재")
	void 정보없음() {
		ExhibitionPlace place = seedPlace("정보없는관PH", uniqueAddr());
		given(placeHoursProvider.fetch(eq(place.getName()), eq(place.getAddress())))
				.willReturn(Optional.of(data(place.getAddress(), WeeklyOpeningHours.empty())));

		placeHoursEnricher.enrichPlaceHours();

		PlaceHours canonical = placeHoursRepository.findByExhibitionPlaceId(place.getId()).orElseThrow();
		assertThat(canonical.getFormatted()).isNull();
		assertThat(canonical.getSyncedAt()).isNotNull();
		assertThat(googlePlaceSnapshotJpaRepository.findByExhibitionPlaceId(place.getId())).isPresent();
		assertThat(canonical.getStatus()).isEqualTo(PlaceHoursStatus.NO_HOURS);
	}

	@Test
	@DisplayName("장소 미발견 → formatted null, NOT_FOUND, 원본 미적재")
	void 미발견() {
		ExhibitionPlace place = seedPlace("없는관PH", uniqueAddr());
		given(placeHoursProvider.fetch(eq(place.getName()), eq(place.getAddress()))).willReturn(Optional.empty());

		placeHoursEnricher.enrichPlaceHours();

		PlaceHours canonical = placeHoursRepository.findByExhibitionPlaceId(place.getId()).orElseThrow();
		assertThat(canonical.getFormatted()).isNull();
		assertThat(canonical.getSyncedAt()).isNotNull();
		assertThat(googlePlaceSnapshotJpaRepository.findByExhibitionPlaceId(place.getId())).isEmpty();
		assertThat(canonical.getStatus()).isEqualTo(PlaceHoursStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("이미 최근 동기화된 전시장은 재호출 대상에서 제외")
	void 최신은_스킵() {
		ExhibitionPlace place = seedPlace("최신관PH", uniqueAddr());
		placeHoursRepository.save(PlaceHours.first(place.getId(), "기존값", PlaceHoursStatus.SUCCEEDED,
				PlaceHoursVendor.GOOGLE, LocalDateTime.now())); // 방금 동기화한 정준행

		placeHoursEnricher.enrichPlaceHours();

		assertThat(placeHoursRepository.findByExhibitionPlaceId(place.getId()).orElseThrow().getFormatted())
				.isEqualTo("기존값");
		verify(placeHoursProvider, never()).fetch(eq(place.getName()), eq(place.getAddress()));
	}

	// ── helpers ──────────────────────────────────────────

	private String uniqueAddr() {
		return "서울 테스트구 테스트로 " + SEQ.getAndIncrement();
	}

	/** 주소가 있는(=조회 대상) 전시장을 만든다. 대상 선별 조건 = address not null + 정준행 없음/만료. */
	private ExhibitionPlace seedPlace(String name, String addr) {
		ExhibitionPlace place = exhibitionPlaceRepository.save(
				ExhibitionPlace.createFromList(name + "-" + SEQ.getAndIncrement(), ExhibitionRegion.SEOUL, null,
						null, null));
		place.enrichDetail(addr, null, null);
		return exhibitionPlaceRepository.save(place);
	}

	private WeeklyOpeningHours everyDaySameTimeButMondayClosed() {
		WeeklyOpeningHours.Builder builder = WeeklyOpeningHours.builder();
		for (DayOfWeek d : new DayOfWeek[] { DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
				DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY }) {
			builder.add(d, LocalTime.of(10, 0), LocalTime.of(18, 0));
		}
		return builder.build();
	}

	/** 구글 응답을 흉내낸 조회 결과 — 파싱된 hours + 스냅샷 필드(placeId·주소). archival이 GOOGLE로 적재하는지 검증용. */
	private PlaceHoursResult data(String addr, WeeklyOpeningHours hours) {
		return new PlaceHoursResult() {
			public PlaceHoursData data() {
				return new PlaceHoursData(hours);
			}

			public String placeId() {
				return "pid-" + addr;
			}

			public String displayNameText() {
				return null;
			}

			public String formattedAddress() {
				return addr;
			}

			public String regularOpeningHoursJson() {
				return null;
			}
		};
	}
}
