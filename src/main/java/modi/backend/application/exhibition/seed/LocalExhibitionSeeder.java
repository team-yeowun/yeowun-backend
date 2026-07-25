package modi.backend.application.exhibition.seed;


import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import lombok.RequiredArgsConstructor;
import modi.backend.support.db.SqlScriptSplitter;

/**
 * 로컬 전시 시드 적재기 — {@code app.local-seed.enabled=true}(로컬 default 프로파일)일 때만 빈으로 뜬다.
 *
 * <p><b>왜 필요한가</b>: {@code application.yaml}에 공공데이터 인증키 기본값이 박혀 있어, 프론트 개발자가 도커로 로컬 기동하면
 * 정기 동기화가 실 data.go.kr을 호출한다(+빈 Gemini 키 random 폴백). 이 시더가 켜지면 그 외부 동기화·릴레이는
 * skip되고({@code ExhibitionSyncScheduler}·{@code ExhibitionOutboxRelay}), <b>classpath 시드 SQL로만</b> 초기화된다 —
 * 로컬 실 API 호출 0. <b>초기 적재의 표준 경로</b>이기도 하다(부팅 카탈로그 동기화는 이 결정으로 삭제됐다).
 *
 * <p><b>동작</b>: Flyway 마이그레이션 이후(ApplicationRunner라 자동) 실행된다. {@code exhibitions}가 <b>비어 있을 때만</b>
 * 스냅샷 + 더미 보강 SQL을 순서대로 적재한다. 비어 있지 않으면 아무것도 하지 않는다 — 재부팅 시 데이터 유지(멱등).
 *
 * <p>mysqldump 스냅샷은 문자열 리터럴에 세미콜론·백슬래시 이스케이프가 들어가므로 {@link SqlScriptSplitter}로 문장을 나누고
 * <b>한 커넥션</b>에서 순서대로 실행한다(세션 변수·charset 일관성).
 */
@Component
@ConditionalOnProperty(name = "app.local-seed.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE) // 외부 동기화(BootSync)는 이 플래그가 켜지면 어차피 skip하나, 시드 우선을 의도로 명시한다.
@RequiredArgsConstructor
public class LocalExhibitionSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalExhibitionSeeder.class);

	/** 적재 순서 — 스냅샷(코어·장소·상세·장르·벤더 원본) → 더미 보강(영업시간·장르 공백·상세 결측). */
	private static final List<String> SEED_SCRIPTS = List.of(
			"db/localseed/seed-local-exhibitions.sql",
			"db/localseed/seed-local-dummy-enrichment.sql");

	private final DataSource dataSource;

	@Override
	public void run(ApplicationArguments args) {
		try {
			runSeed();
		} catch (RuntimeException | SQLException e) {
			// 로컬 편의 기능이라 앱 기동을 막지 않는다 — 실패해도 부팅은 계속(수동 리셋 스크립트로 복구 가능).
			log.warn("로컬 전시 시드 적재 실패(기동은 계속): {}", e.getMessage(), e);
		}
	}

	private void runSeed() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			if (exhibitionCount(connection) > 0) {
				log.info("로컬 전시 시드 skip — exhibitions에 데이터가 이미 있음(멱등, 재적재 안 함)");
				return;
			}
			int totalStatements = 0;
			for (String path : SEED_SCRIPTS) {
				totalStatements += executeScript(connection, path);
			}
			log.info("로컬 전시 시드 적재 완료 — 스크립트 {}개 / 문장 {}개 (외부 API 호출 없음)",
					SEED_SCRIPTS.size(), totalStatements);
		}
	}

	private long exhibitionCount(Connection connection) throws SQLException {
		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM exhibitions")) {
			return rs.next() ? rs.getLong(1) : 0;
		}
	}

	private int executeScript(Connection connection, String classpath) throws SQLException {
		String sql = readClasspath(classpath);
		List<String> statements = SqlScriptSplitter.split(sql);
		try (Statement st = connection.createStatement()) {
			for (String statement : statements) {
				st.execute(statement);
			}
		}
		return statements.size();
	}

	private String readClasspath(String classpath) {
		try (var in = new ClassPathResource(classpath).getInputStream()) {
			return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException("로컬 시드 SQL을 읽지 못했습니다: " + classpath, e);
		}
	}
}
