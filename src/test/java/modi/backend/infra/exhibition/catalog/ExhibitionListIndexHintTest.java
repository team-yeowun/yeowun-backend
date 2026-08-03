package modi.backend.infra.exhibition.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.domain.exhibition.catalog.ExhibitionQuery;
import modi.backend.domain.exhibition.catalog.ExhibitionQueryRepository;
import modi.backend.domain.exhibition.catalog.ExhibitionRegion;
import modi.backend.domain.exhibition.catalog.ExhibitionSort;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * 목록 슬라이스 SQL의 <b>인덱스 힌트 범위</b>와 <b>투영(SELECT 목록)의 커버 불가성</b>을 고정한다.
 * 목록 계획이 조용히 무너지는 두 경로를 각각 막는 것이 목적이다.
 *
 * <p><b>1) 힌트는 "지역이 정확히 1개"일 때만 붙어야 한다.</b>
 * V49가 region을 exhibitions로 비정규화한 뒤 지역 술어는 정렬 인덱스 스캔의 잔여 필터가 됐고, 비용이
 * <b>"오늘로부터 그 지역의 최근 진행 전시까지의 날짜 간격"</b>에 비례하게 됐다(진행 중 0건이면 상한 없음).
 * 1M 실측 최신순: 서울 0.3ms · 제주 657ms · 세종 2,726ms · 충남 17,501ms(인기순 충남 50,505ms).
 * V51 복합 인덱스가 이를 0.1~0.3ms로 되돌리는데, <b>옵티마이저는 스스로 그 인덱스를 고르지 못한다</b>
 * (1M 제주 최신순 힌트 없이 807ms) — 그래서 지목이 필요하다. 반대로 지역이 2개 이상이면 지목이 <b>해롭다</b>
 * (구간이 쪼개져 정렬이 되살아난다 — 밀집 조합 1,339ms vs 지목 안 함 0.19ms). 비지역 경로는 힌트 유무로
 * 계획이 동일했다(1M 17경로, 최대 차이 0.10ms↔0.12ms). 즉 <b>범위가 곧 정확성</b>이라 범위를 고정한다.
 *
 * <p><b>count 쪽은 반대 방향의 범위가 있다</b>: 지역 2개 이상 + 키워드 없음일 때만 V50 커버링 인덱스를
 * 지목한다(region IN 2개부터 옵티마이저가 커버링을 버린다 — 1M 실측 1,919ms → 지목 시 350ms). 0·1개는
 * 스스로 고르고, 키워드가 끼면 지목이 풀 스캔 전환으로 해롭다. 목록 힌트와 count 힌트는 <b>서로 다른
 * 인덱스</b>를 가리키므로 교차 오염(목록 인덱스가 count에, 커버링이 목록에)을 각 테스트가 막는다.
 *
 * <p><b>2) 투영을 좁히면 안 된다.</b> 목록은 엔티티 전 컬럼을 읽는데, 이걸 {@code id}만으로 좁히면
 * 정렬 인덱스가 "커버링"이 되면서 옵티마이저가 통과 행 수를 42로 추정하고 전 인덱스 주사로 전환한다 —
 * 1M 실측 0.283ms → <b>6,824ms(24,100배)</b>. 힌트로는 이걸 막을 수 없다(힌트가 없어도 일어나고,
 * {@code USE INDEX}는 애초에 풀 스캔을 막지 못한다). 그래서 <b>투영이 어떤 인덱스로도 커버되지 않음</b>을
 * 스키마와 대조해 직접 고정한다(아래 {@code 목록_투영은_어떤_인덱스로도_커버되지_않는다}).
 *
 * <p>계획(EXPLAIN)이 기대대로 나오는지는 여기서 보지 않는다 — 테스트 DB는 행이 몇 개뿐이라 어떤 계획도
 * 의미가 없다. 그건 볼륨이 있는 측정 DB에서 {@code loadtest/probe/explain.sql}이 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.exhibition.enrich.scheduling-enabled=false",
		"spring.jpa.properties.hibernate.session_factory.statement_inspector="
				+ "modi.backend.infra.exhibition.catalog.ExhibitionListIndexHintTest$SqlCapture"
})
class ExhibitionListIndexHintTest {

	@Autowired
	ExhibitionQueryRepository exhibitionQueryRepository;
	@Autowired
	DataSource dataSource;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@BeforeEach
	void setUp() {
		SqlCapture.reset();
	}

	/**
	 * 기대값을 <b>상수가 아니라 리터럴</b>로 적는다. 프로덕션 상수를 그대로 참조하면 상수 값이 잘못 바뀌어도
	 * 테스트가 함께 따라가 통과한다(= 아무것도 검증하지 않는다). 이름이 바뀌어야 하는 변경이라면
	 * 여기도 함께 고치는 것이 맞다 — 그 강제가 이 테스트의 일부다.
	 */
	private static final String REGION_LATEST_INDEX = "idx_exhibitions_region_start_id";
	private static final String REGION_ENDING_INDEX = "idx_exhibitions_region_end_id";
	private static final String REGION_POPULAR_INDEX = "idx_exhibitions_region_views_id";
	private static final String COUNT_COVER_INDEX = "idx_exhibitions_count_cover";

	@Test
	@DisplayName("지역 1개 목록에는 그 정렬 축의 V51 복합 인덱스 힌트가 실린다 — 세 축 각각")
	void 지역_1개_목록에_정렬축별_복합인덱스_힌트가_실린다() {
		assertSliceHint(ExhibitionSort.LATEST, List.of(ExhibitionRegion.JEJU), REGION_LATEST_INDEX);
		assertSliceHint(ExhibitionSort.ENDING, List.of(ExhibitionRegion.JEJU), REGION_ENDING_INDEX);
		assertSliceHint(ExhibitionSort.POPULAR, List.of(ExhibitionRegion.JEJU), REGION_POPULAR_INDEX);
		// 거리순은 DB가 정렬하지 않아 기본 축(최신순)으로 수렴한다 — ORDER BY와 힌트가 같은 축을 봐야 한다.
		assertSliceHint(ExhibitionSort.DISTANCE, List.of(ExhibitionRegion.JEJU), REGION_LATEST_INDEX);
	}

	@Test
	@DisplayName("지역이 없으면 힌트가 붙지 않는다 — 붙일 이유가 1M에서 측정되지 않았다")
	void 비지역_목록에는_힌트가_붙지_않는다() {
		for (ExhibitionSort sort : ExhibitionSort.values()) {
			SqlCapture.reset();
			exhibitionQueryRepository.searchSlice(sliceQuery(sort, List.of()), 21);

			assertThat(exhibitionSelect())
					.as("정렬 축 %s · 지역 없음: 힌트를 붙이면 옵티마이저의 후보를 근거 없이 좁힌다", sort)
					.doesNotContain("use index");
		}
	}

	@Test
	@DisplayName("지역이 2개 이상이면 힌트가 붙지 않는다 — 붙이면 정렬이 되살아나 후퇴한다")
	void 다중지역_목록에는_힌트가_붙지_않는다() {
		SqlCapture.reset();
		exhibitionQueryRepository.searchSlice(
				sliceQuery(ExhibitionSort.LATEST, List.of(ExhibitionRegion.SEOUL, ExhibitionRegion.GYEONGGI)), 21);

		assertThat(exhibitionSelect())
				.as("다중 지역에 복합 인덱스를 강제하면 구간이 쪼개져 정렬이 되살아난다"
						+ "(1M 밀집 조합 실측 0.19ms → 1,339ms)")
				.doesNotContain("use index");
	}

	@Test
	@DisplayName("지역 0·1개 count에는 힌트가 붙지 않는다 — 옵티마이저가 스스로 커버링을 고른다(1M 확인)")
	void 단일지역_count에는_힌트가_붙지_않는다() {
		for (List<ExhibitionRegion> regions : List.of(List.<ExhibitionRegion>of(), List.of(ExhibitionRegion.JEJU))) {
			SqlCapture.reset();
			exhibitionQueryRepository.count(sliceQuery(ExhibitionSort.LATEST, regions));

			String sql = exhibitionSelect();

			assertThat(sql).startsWith("select count(");
			assertThat(sql)
					.as("지역 %s count: 붙일 이유가 측정되지 않았고, 목록 인덱스를 강제하면 커버링을 못 탄다"
							+ "(500k 실측 138ms → 907ms)", regions)
					.doesNotContain("use index");
		}
	}

	@Test
	@DisplayName("지역 2개 이상 count에는 V50 커버링 인덱스 힌트가 실린다 — 옵티마이저가 커버링을 버리는 유일한 경로")
	void 다중지역_count에는_커버링인덱스_힌트가_실린다() {
		SqlCapture.reset();
		exhibitionQueryRepository.count(
				sliceQuery(ExhibitionSort.LATEST, List.of(ExhibitionRegion.SEOUL, ExhibitionRegion.GYEONGGI)));

		String sql = exhibitionSelect();

		assertThat(sql).startsWith("select count(");
		assertThat(sql)
				.as("region IN이 2개가 되는 순간 옵티마이저가 idx_exhibitions_type_owner로 도망간다"
						+ "(1M 실측 1,919ms → 지목 시 350ms). USE INDEX와 FORCE INDEX는 이 쿼리에서 계획이 동일하다")
				.contains("use index (" + COUNT_COVER_INDEX + ")");
	}

	@Test
	@DisplayName("다중지역이라도 키워드가 끼면 count에 힌트가 붙지 않는다 — 커버링이 원리적으로 불가한 경로")
	void 키워드_count에는_힌트가_붙지_않는다() {
		SqlCapture.reset();
		exhibitionQueryRepository.count(new ExhibitionQuery("전시", LocalDate.now(), null,
				List.of(ExhibitionRegion.SEOUL, ExhibitionRegion.GYEONGGI), List.of(), null, null, null,
				ExhibitionSort.LATEST, null, null, null));

		String sql = exhibitionSelect();

		assertThat(sql).startsWith("select count(");
		assertThat(sql)
				.as("키워드 술어(title LIKE·전시장 서브쿼리)는 커버링 밖이라, 지목하면 풀 테이블 스캔으로 전환한다"
						+ "(1M EXPLAIN: type=ALL)")
				.doesNotContain("use index");
	}

	/**
	 * <b>투영이 어떤 인덱스로도 커버되지 않는지</b>를 스키마와 대조한다. 목록 계획을 지키는 두 번째 장치다 —
	 * 투영이 어떤 인덱스에 다 들어가는 순간 옵티마이저가 그 인덱스를 전부 주사하는 계획으로 전환한다
	 * (1M 실측 0.283ms → 6,824ms). InnoDB 보조 인덱스는 PK를 암묵 포함하므로 비교 시 {@code id}를 더해 준다.
	 *
	 * <p><b>이 검사가 지금 무엇을 실제로 막고 있는지 정직하게 적는다</b>: 현재 투영은 19컬럼이고 MySQL의
	 * 인덱스는 <b>최대 16컬럼</b>이라, 지금 스키마에서 이 검사는 <b>구조적으로 통과할 수밖에 없다</b>
	 * (전 컬럼을 담는 인덱스는 애초에 만들 수 없다 — 실제로 시도했고 마이그레이션이 실패했다).
	 * 즉 이 검사의 값은 "지금 스키마 감시"가 아니라 <b>투영이 좁아지는 순간 깨지는 것</b>이다.
	 * 그 순간을 잡는 것이 목적이므로 아래 음성 대조군으로 <b>검사에 이가 있는지</b>를 함께 고정한다.
	 */
	@Test
	@DisplayName("목록 투영은 어떤 인덱스로도 커버되지 않는다 — projection을 좁히면 이 테스트가 먼저 깨진다")
	void 목록_투영은_어떤_인덱스로도_커버되지_않는다() throws SQLException {
		exhibitionQueryRepository.searchSlice(sliceQuery(ExhibitionSort.LATEST, List.of()), 21);
		Set<String> projected = projectedColumns(exhibitionSelect());

		assertThat(projected)
				.as("목록 투영이 비어 보인다 — SQL 파싱이 깨졌다면 이 테스트는 공허하다")
				.hasSizeGreaterThan(5);

		Map<String, Set<String>> indexes = exhibitionIndexColumns();
		assertThat(indexes).as("exhibitions 인덱스를 읽지 못했다").isNotEmpty();

		for (Map.Entry<String, Set<String>> index : indexes.entrySet()) {
			assertThat(coveredBy(index.getValue(), projected))
					.as("인덱스 %s(%s)가 목록 투영 %s를 전부 담고 있다 — 이 인덱스를 전부 주사하는 계획으로 "
							+ "옵티마이저가 전환할 수 있다(1M 실측 24,100배 후퇴). 투영을 좁히지 말고, "
							+ "인덱스를 넓혔다면 그 결정을 다시 보라.", index.getKey(), index.getValue(), projected)
					.isFalse();
		}
	}

	/**
	 * 음성 대조군 — 위 검사에 이가 있는지 본다. 좁은 투영({@code id, end_date})은 실제 스키마의
	 * {@code idx_exhibitions_end_id}에 <b>커버된다고 판정되어야</b> 한다. 이게 통과하지 않으면
	 * 위 테스트는 "무엇을 넣어도 통과하는" 공허한 검사이고, 투영이 좁아져도 아무도 못 잡는다.
	 */
	@Test
	@DisplayName("커버 판정에 이가 있다 — 좁은 투영은 커버된다고 판정된다(음성 대조군)")
	void 커버_판정은_좁은_투영을_잡아낸다() throws SQLException {
		Set<String> endIndexColumns = exhibitionIndexColumns().get("idx_exhibitions_end_id");
		assertThat(endIndexColumns).as("대조군이 쓰는 인덱스가 스키마에 없다").isNotNull();

		assertThat(coveredBy(endIndexColumns, Set.of("id", "end_date")))
				.as("(end_date, id) 인덱스는 {id, end_date} 투영을 커버한다 — 이걸 못 잡으면 판정이 공허하다")
				.isTrue();
		assertThat(coveredBy(endIndexColumns, Set.of("id", "end_date", "title")))
				.as("title은 그 인덱스에 없다 — 커버되지 않는다고 판정해야 한다")
				.isFalse();
	}

	/** 인덱스 구성 컬럼이 투영을 전부 담는가. InnoDB 보조 인덱스는 PK(id)를 암묵 포함한다. */
	private static boolean coveredBy(Set<String> indexColumns, Set<String> projected) {
		Set<String> covered = new LinkedHashSet<>(indexColumns);
		covered.add("id");
		return covered.containsAll(projected);
	}

	@Test
	@DisplayName("힌트가 지목하는 인덱스 세 개가 실제 스키마에 있다 — 마이그레이션이 이름을 바꾸면 잡힌다")
	void 힌트가_지목하는_인덱스가_실제로_존재한다() throws SQLException {
		Set<String> indexes = exhibitionIndexColumns().keySet();

		assertThat(indexes).contains(REGION_LATEST_INDEX, REGION_ENDING_INDEX, REGION_POPULAR_INDEX);
		assertThat(indexes)
				.as("count가 탈 커버링 인덱스(V50)도 있어야 한다 — 없으면 count 경로가 적용되지 않은 것이다")
				.contains(COUNT_COVER_INDEX);
	}

	@Test
	@DisplayName("틀린 인덱스 이름은 ERROR 1176으로 실패한다 — 힌트가 조용히 무시되지 않는다는 증명")
	void 틀린_인덱스_이름은_에러로_드러난다() {
		// 음성 대조군. 이 검사가 통과하지 않으면 위 테스트들은 "이름이 맞다"만 보는 셈이고,
		// 이름이 틀렸을 때 프로덕션이 조용히 느려지는 시나리오를 아무도 막지 못한다.
		assertThatThrownBy(() -> execute("""
				select e.id from exhibitions e use index (idx_this_index_does_not_exist) where e.id = 1
				"""))
				.isInstanceOf(SQLSyntaxErrorException.class)
				.hasMessageContaining("doesn't exist in table");
	}

	private void assertSliceHint(ExhibitionSort sort, List<ExhibitionRegion> regions, String expectedIndex) {
		SqlCapture.reset();
		exhibitionQueryRepository.searchSlice(sliceQuery(sort, regions), 21);

		assertThat(exhibitionSelect())
				.as("정렬 축 %s · 지역 %s의 슬라이스는 %s를 후보로 못 박아야 한다", sort, regions, expectedIndex)
				.contains("use index (" + expectedIndex + ")");
	}

	/** {@code select e1_0.id,e1_0.title,… from exhibitions …}의 투영 컬럼명 집합. 별칭 접두사는 떼어낸다. */
	private static Set<String> projectedColumns(String sql) {
		int from = sql.indexOf(" from exhibitions");
		assertThat(from).as("목록 SQL에서 from 절을 찾지 못했다: %s", sql).isGreaterThan(0);
		String selectList = sql.substring("select ".length(), from);
		Set<String> columns = new LinkedHashSet<>();
		for (String raw : selectList.split(",")) {
			String column = raw.trim();
			int dot = column.lastIndexOf('.');
			if (dot >= 0) {
				column = column.substring(dot + 1);
			}
			if (!column.isBlank() && column.matches("[a-z0-9_]+")) {
				columns.add(column);
			}
		}
		return columns;
	}

	/** exhibitions의 인덱스 이름 → 구성 컬럼 집합. */
	private Map<String, Set<String>> exhibitionIndexColumns() throws SQLException {
		Map<String, Set<String>> indexes = new TreeMap<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("""
						select index_name, group_concat(column_name order by seq_in_index) as cols
						  from information_schema.statistics
						 where table_schema = database() and table_name = 'exhibitions'
						 group by index_name
						""")) {
			while (rs.next()) {
				String cols = rs.getString("cols");
				indexes.put(rs.getString("index_name"),
						new LinkedHashSet<>(Arrays.asList(cols == null ? new String[0] : cols.split(","))));
			}
		}
		return indexes;
	}

	private void execute(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	/** 캡처된 SQL 중 exhibitions를 읽는 문장 하나. (연관 로딩 등 곁가지 문장을 걸러낸다) */
	private static String exhibitionSelect() {
		List<String> candidates = SqlCapture.captured().stream()
				.map(sql -> sql.toLowerCase(Locale.ROOT))
				.filter(sql -> sql.startsWith("select") && sql.contains("from exhibitions "))
				.toList();
		assertThat(candidates).as("exhibitions 조회 SQL이 캡처되지 않았다").isNotEmpty();
		return candidates.getFirst();
	}

	private ExhibitionQuery sliceQuery(ExhibitionSort sort, List<ExhibitionRegion> regions) {
		return new ExhibitionQuery(null, LocalDate.now(), null, regions, List.of(), null, null, null,
				sort, null, null, null);
	}

	/** Hibernate가 JDBC로 넘기기 직전의 SQL을 그대로 모은다 — 힌트 주입은 이 단계에서 이미 끝나 있다. */
	public static class SqlCapture implements StatementInspector {

		private static final List<String> CAPTURED = new ArrayList<>();

		static synchronized void reset() {
			CAPTURED.clear();
		}

		static synchronized List<String> captured() {
			return List.copyOf(CAPTURED);
		}

		@Override
		public synchronized String inspect(String sql) {
			CAPTURED.add(sql);
			return sql;
		}
	}
}
