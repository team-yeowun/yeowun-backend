package modi.backend.infra.exhibition.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * V61 인기 점수 인덱스의 <b>모양</b>을 고정한다.
 *
 * <p>인기순은 {@code popularity_score DESC, id DESC}로만 읽는다. 인덱스를 같은 방향(내림차순)으로 만들어
 * 역순 스캔 없이 정순으로 읽게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.exhibition.enrich.scheduling-enabled=false")
class ExhibitionPopularityIndexTest {

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	ExhibitionCatalogClient exhibitionCatalogClient;

	@Test
	@DisplayName("인기 점수 인덱스는 (popularity_score DESC, id DESC)다")
	void 인기_점수_인덱스는_읽는_방향과_같은_내림차순이다() {
		assertThat(columnsOf("idx_exhibitions_popularity")).isEqualTo("popularity_score D,id D");
	}

	@Test
	@DisplayName("지역 인기 인덱스는 (region, popularity_score DESC, id DESC)다")
	void 지역_인기_인덱스도_점수는_내림차순이다() {
		assertThat(columnsOf("idx_exhibitions_region_popularity")).isEqualTo("region A,popularity_score D,id D");
	}

	@Test
	@DisplayName("점수는 앱이 쓰지 않고 MySQL이 두 수에서 계산하는 생성 컬럼이다(가중치 0.7, 0.3)")
	void 점수는_생성_컬럼이다() {
		String expression = jdbc.queryForObject("""
				select generation_expression from information_schema.columns
				 where table_schema = database() and table_name = 'exhibitions' and column_name = 'popularity_score'
				""", String.class);

		assertThat(expression).isNotNull();
		assertThat(expression.replace("`", "")).contains("bookmark_count * 0.7").contains("our_view_count * 0.3");
	}

	private String columnsOf(String indexName) {
		return jdbc.queryForObject("""
				select group_concat(concat(column_name, ' ', collation) order by seq_in_index)
				  from information_schema.statistics
				 where table_schema = database() and table_name = 'exhibitions' and index_name = ?
				""", String.class, indexName);
	}
}
