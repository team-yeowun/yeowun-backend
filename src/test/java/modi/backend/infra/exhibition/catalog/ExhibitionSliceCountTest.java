package modi.backend.infra.exhibition.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jakarta.persistence.EntityManagerFactory;
import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionCategory;
import modi.backend.domain.exhibition.catalog.ExhibitionPlaceRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionSort;
import modi.backend.domain.exhibition.catalog.ExhibitionTestFactory;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * 키셋 슬라이스가 <b>SQL을 한 번만 실행하는지</b> 검증한다(@SpringBootTest + Testcontainers-MySQL).
 *
 * <p><b>배경</b>: {@code findAll(spec, Pageable)}은 {@code Page}를 만들며 총 건수 count를 함께 실행한다.
 * 슬라이스는 {@code getContent()}만 쓰고 그 값을 버렸는데, Spring Data가 "첫 페이지이고 결과가 페이지 크기보다
 * 적으면" count를 생략해 주는 탓에 <b>데이터가 적을 땐 드러나지 않았다</b>. 페이지가 꽉 차는 순간 — 즉 다음
 * 페이지가 있는 실사용에서 — 매 요청 count가 두 번 나갔다(하나는 슬라이스가 버리는 것, 하나는 totalCount용).
 *
 * <p>그래서 이 테스트는 <b>페이지를 반드시 채운다</b>. 그러지 않으면 회귀가 나도 통과해 버린다.
 * 실행 횟수는 Hibernate 통계의 JDBC 문 수로 센다 — "몇 번 나갔나"를 직접 보는 가장 단순한 자다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.exhibition.enrich.scheduling-enabled=false",
		"spring.jpa.properties.hibernate.generate_statistics=true"
})
class ExhibitionSliceCountTest {

	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired
	ExhibitionQueryRepository exhibitionQueryRepository;
	@Autowired
	ExhibitionRepository exhibitionRepository;
	@Autowired
	ExhibitionPlaceRepository exhibitionPlaceRepository;
	@Autowired
	EntityManagerFactory entityManagerFactory;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	private Statistics statistics;

	@BeforeEach
	void setUp() {
		statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
	}

	@Test
	@DisplayName("키셋 슬라이스는 페이지가 꽉 차도 SQL을 한 번만 실행한다(버려지는 count 없음)")
	void 슬라이스는_SQL을_한_번만_실행한다() {
		LocalDate today = LocalDate.now();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "슬라이스검증관", ExhibitionRegion.BUSAN);
		for (int i = 0; i < 5; i++) {
			exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, "slice-" + SEQ.getAndIncrement(),
					"슬라이스 검증 전시", today.minusDays(i), today.plusDays(30), ExhibitionCategory.PAINTING));
		}

		// 요청 3건인데 대상은 5건 이상 — 반드시 페이지가 찬다(예전 구현이 count를 실행하던 조건).
		long before = statistics.getPrepareStatementCount();
		List<Exhibition> slice = exhibitionQueryRepository.searchSlice(ongoingQuery(today), 3);
		long executed = statistics.getPrepareStatementCount() - before;

		assertThat(slice).hasSize(3);
		assertThat(executed)
				.as("슬라이스는 총 건수를 쓰지 않으므로 조회 1회로 끝나야 한다")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("totalCount용 count는 그대로 한 번 실행된다")
	void count는_한_번_실행된다() {
		LocalDate today = LocalDate.now();
		Long placeId = ExhibitionTestFactory.placeId(exhibitionPlaceRepository, "슬라이스검증관", ExhibitionRegion.BUSAN);
		exhibitionRepository.save(ExhibitionTestFactory.catalog(placeId, "slice-" + SEQ.getAndIncrement(),
				"슬라이스 검증 전시", today.minusDays(1), today.plusDays(30), ExhibitionCategory.PAINTING));

		long before = statistics.getPrepareStatementCount();
		long total = exhibitionQueryRepository.count(ongoingQuery(today));
		long executed = statistics.getPrepareStatementCount() - before;

		assertThat(total).isPositive();
		assertThat(executed).isEqualTo(1);
	}

	private ExhibitionQuery ongoingQuery(LocalDate today) {
		return new ExhibitionQuery(null, today, null, List.of(), List.of(), null, null, null, ExhibitionSort.LATEST,
				null, null, null);
	}
}
