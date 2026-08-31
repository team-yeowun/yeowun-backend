package modi.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 시크릿이 <b>컨테이너까지</b> 전달되는지 고정한다.
 *
 * <p><b>왜 이 테스트가 있나</b>: 이 레포의 배포는 3층이고 <b>층마다 따로 배선</b>해야 한다 —
 * ① GitHub Secret → ② {@code deploy.yml}의 {@code envs:} + {@code .env} 기록 → ③ <b>{@code compose.yaml}의
 * {@code environment:} 선언</b>. compose의 {@code .env}는 <b>compose 파일 안의 {@code ${VAR}} 치환용일 뿐
 * 컨테이너에 자동 주입되지 않는다</b>. ③을 빠뜨리면 앞의 둘이 멀쩡해도 앱은 빈 값을 본다.
 *
 * <p>실제로 2026-07-19 네이버 로그인 운영 장애가 이것이었다. 서버 {@code .env}엔 값이 있었지만
 * ({@code NAVER_CLIENT_ID} len=20, {@code NAVER_CLIENT_SECRET} len=10) 컨테이너 안에선 <b>둘 다 len=0</b>이라
 * 네이버가 {@code {error=invalid_request, error_description=client_secret is missing.}}로 거부했고,
 * 사용자에겐 {@code OAUTH_COMMUNICATION_FAILED}로만 보였다. 같은 날 장르 2차 공급자 키도
 * 동일 유형으로 깨졌다(be6cf67) — <b>재발형 함정</b>이라 테스트로 못 박는다.
 *
 * <p>이 테스트가 깨지면: 새로 추가한 env를 {@code compose.yaml} 앱 서비스(app-1·app-2 공유 앵커)
 * {@code environment:}에 선언하라.
 */
class ComposeSecretWiringTest {

	/**
	 * 앱이 뜨려면(혹은 핵심 기능이 동작하려면) <b>반드시 컨테이너까지</b> 도달해야 하는 키.
	 * 여기 없는 키(GC_*, CULTURE_API_KEY 등)는 미주입 시 폴백이 있어 의도적으로 제외한다.
	 */
	private static final List<String> MUST_REACH_CONTAINER = List.of(
			"KAKAO_CLIENT_SECRET",
			"NAVER_CLIENT_ID",
			"NAVER_CLIENT_SECRET",
			"JWT_SECRET");

	/** 앱 컨테이너는 두 대 — 같은 env 앵커를 공유하지만, 앵커가 풀리는 사고까지 잡도록 각각 검증한다. */
	private static final List<String> APP_SERVICES = List.of("app-1", "app-2");

	@Test
	@DisplayName("소셜 로그인 시크릿이 compose.yaml 앱 서비스 두 대로 전달된다")
	void oauth_secrets_are_passed_through_to_the_container() {
		for (String service : APP_SERVICES) {
			List<String> declared = appServiceEnvironment(service);

			assertThat(declared)
					.as(".env에만 있고 compose.yaml에 없으면 컨테이너는 빈 값을 본다(2026-07-19 네이버 로그인 장애)")
					.allSatisfy(entry -> assertThat(entry).contains("="));

			for (String key : MUST_REACH_CONTAINER) {
				assertThat(declared)
						.as("compose.yaml %s 서비스 environment에 %s 선언이 없다 → 컨테이너에 주입되지 않는다", service, key)
						.anySatisfy(entry -> assertThat(entry).startsWith(key + "="));
			}
		}
	}

	@Test
	@DisplayName("client-id는 빈 문자열로 덮이지 않도록 compose 기본값을 갖는다")
	void client_id_has_non_empty_compose_default() {
		// ⚠️ `${NAVER_CLIENT_ID:-}` 처럼 빈 문자열을 넘기면 Spring이 "변수 존재 = 빈값"으로 보고
		//    application.yaml의 기본값을 덮어써 버린다(compose.yaml 주석 참고). id는 공개값이라 기본값을 박아 둔다.
		String entry = appServiceEnvironment("app-1").stream()
				.filter(e -> e.startsWith("NAVER_CLIENT_ID="))
				.findFirst()
				.orElseThrow(() -> new AssertionError("compose.yaml에 NAVER_CLIENT_ID 선언이 없다"));

		assertThat(entry)
				.as("빈 기본값이면 application.yaml의 공개 client-id가 빈 문자열로 덮인다")
				.doesNotContain(":-}")
				.doesNotEndWith(":-'");
	}

	/** compose.yaml의 {@code services.<앱>.environment} 목록을 읽는다(YAML 앵커는 SnakeYAML이 해소). */
	@SuppressWarnings("unchecked")
	private static List<String> appServiceEnvironment(String serviceName) {
		Path compose = Path.of("compose.yaml");
		assertThat(compose).as("프로젝트 루트에서 compose.yaml을 찾지 못했다").exists();
		try {
			Map<String, Object> root = new Yaml().load(Files.readString(compose));
			Map<String, Object> services = (Map<String, Object>) root.get("services");
			Map<String, Object> app = (Map<String, Object>) services.get(serviceName);
			return (List<String>) app.get("environment");
		} catch (Exception e) {
			throw new IllegalStateException("compose.yaml의 " + serviceName + " 서비스 environment를 읽지 못했다", e);
		}
	}
}
