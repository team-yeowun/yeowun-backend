package modi.backend.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

import modi.backend.support.db.RoutingDataSource;

/**
 * 읽기/쓰기 분리 배선 - 복제본 주소가 있을 때만 켜진다.
 *
 * <ul>
 *   <li>{@code app.datasource.replica.url} 이 없으면 이 설정 전체가 미등록 - 부트의 기본 DataSource 자동구성이
 *       그대로 살아 로컬·CI·기존 compose 의 동작이 한 줄도 바뀌지 않는다</li>
 *   <li>있으면 원본·복제본 두 풀을 직접 만들고 라우팅을 {@code @Primary} 로 올린다 - 이 순간 자동구성은 물러난다</li>
 *   <li>지연 커넥션 프록시로 감싸는 것이 필수 - 감싸지 않으면 트랜잭션이 시작되기 전에 커넥션이 잡혀
 *       readOnly 판정이 항상 원본으로 떨어진다</li>
 *   <li>마이그레이션은 readOnly 가 아니라 자동으로 원본 - 복제본에서 Flyway 를 돌리지 않는다</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.datasource.replica", name = "url")
public class DataSourceConfig {

	@Bean
	public DataSource masterDataSource(
			@Value("${spring.datasource.url}") String url,
			@Value("${spring.datasource.username}") String username,
			@Value("${spring.datasource.password}") String password,
			@Value("${spring.datasource.hikari.maximum-pool-size:20}") int poolSize) {
		return pool(url, username, password, poolSize, "master");
	}

	@Bean("replicaDataSource")
	public DataSource replicaDataSource(
			@Value("${app.datasource.replica.url}") String url,
			@Value("${app.datasource.replica.username:${spring.datasource.username}}") String username,
			@Value("${app.datasource.replica.password:${spring.datasource.password}}") String password,
			@Value("${app.datasource.replica.maximum-pool-size:10}") int poolSize) {
		return pool(url, username, password, poolSize, "replica");
	}

	@Bean
	@Primary
	public DataSource dataSource(
			@Qualifier("masterDataSource") DataSource masterDataSource,
			@Qualifier("replicaDataSource") DataSource replicaDataSource) {
		RoutingDataSource routing = new RoutingDataSource(masterDataSource, replicaDataSource);
		routing.afterPropertiesSet();
		return new LazyConnectionDataSourceProxy(routing);
	}

	private static HikariDataSource pool(String url, String username, String password, int poolSize, String name) {
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(url);
		dataSource.setUsername(username);
		dataSource.setPassword(password);
		dataSource.setMaximumPoolSize(poolSize);
		dataSource.setPoolName("hikari-" + name);
		return dataSource;
	}
}
