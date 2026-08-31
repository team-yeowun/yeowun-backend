package modi.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 스위트 전체가 공유하는 컨테이너 - 기술마다 하나.
 *
 * <ul>
 *   <li>테스트 클래스마다 @Container 를 띄우지 않는다 - 클래스 수만큼 컨테이너가 늘면 기동 시간이 스위트 시간의 대부분이 된다</li>
 *   <li>공유의 대가는 잔여물이라 스트림 키 삭제와 그룹 재생성이 매 테스트의 책임이 된다(IngestionTestSupport)</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	public MySQLContainer mysqlContainer() {
		// 전체 스위트는 여러 @SpringBootTest 컨텍스트(각자 Hikari 풀)를 캐시해 동시에 살려둔다 —
		// 단일 컨테이너의 기본 max_connections(151)가 병목이 되어 간헐적 커넥션 타임아웃이 나므로 상한을 올린다(용량만 확대).
		return new MySQLContainer(DockerImageName.parse("mysql:latest"))
				.withCommand("--max-connections=1000", "--default-time-zone=+09:00");
	}

	/**
	 * 스위트 전체가 공유하는 Redis 하나. 수집 파이프라인의 이벤트 스트림이 실물로 돈다.
	 *
	 * <p>임베디드 Redis 라이브러리는 쓰지 않는다 - 컨슈머 그룹과 미처리 목록에서 실제 구현과 동작이 갈린다.
	 */
	@Bean
	@ServiceConnection(name = "redis")
	public GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
				.withExposedPorts(6379)
				.withCommand("redis-server", "--appendonly", "yes");
	}
}
