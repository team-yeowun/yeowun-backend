package modi.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 소셜 로그인 client-id의 <b>yaml 기본값</b>이 프론트가 쓰는 공개값과 같은지 고정한다.
 *
 * <p><b>왜 이 테스트가 있나</b>: client-id는 프론트(authorize 요청)와 백엔드(토큰 교환)가 <b>같은 값</b>을 써야 한다 —
 * 프론트가 A 앱으로 받은 code를 백엔드가 B 앱으로 교환하면 네이버가 거부한다. 그런데 값이 두 저장소에 복제돼 있어
 * 한쪽만 바뀌면 조용히 어긋난다. 실제로 네이버 앱이 새로 발급되며 프론트만 갱신돼
 * 운영 로그인이 {@code OAUTH_COMMUNICATION_FAILED}로 깨졌다(구 기본값 {@code ZRdsYHmN3ncMkx2F6353}).
 *
 * <p>백엔드 테스트가 프론트 번들을 볼 수는 없으므로, 여기서는 <b>기대값을 못 박는 것</b>까지 한다.
 * 값을 바꿀 일이 생기면 이 테스트가 같이 깨지므로 "프론트도 함께 바꿨나"를 되묻게 된다.
 *
 * <p><b>운영 값 검증법</b>(콘솔 없이 자격증명 쌍을 확인하는 법 — 이번 조사에서 확인):
 * <pre>
 * curl -s -X POST https://nid.naver.com/oauth2.0/token \
 *   -d grant_type=refresh_token -d client_id=&lt;ID&gt; -d client_secret=&lt;SECRET&gt; -d refresh_token=DUMMY
 * </pre>
 * {@code invalid refresh_token} → 자격증명 쌍이 유효(더미 토큰만 거부됨).
 * {@code invalid client_info} → id/secret 쌍이 틀림.
 */
class OAuthClientIdDefaultTest {

	/** 프론트 {@code VITE_NAVER_CLIENT_ID}와 반드시 동일해야 하는 공개값(네이버 앱 "여운"). */
	private static final String NAVER_PUBLIC_CLIENT_ID = "x5SSFHxy06Npeieb9JlA";

	/**
	 * 프론트 카카오 REST 키와 동일해야 하는 공개값(카카오 비즈앱 "여운").
	 *
	 * <p>구 테스트앱 "여운-TEST"({@code bba3e1d9…})는 카카오 콘솔에서 삭제됐다 — 토큰 교환이
	 * {@code KOE101 Not exist client_id}로 거부된다(2026-07-21 운영 장애).
	 */
	private static final String KAKAO_PUBLIC_CLIENT_ID = "bd5949f0127dd8ae068263f6ec1b5edc";

	@Test
	@DisplayName("네이버 client-id 기본값이 프론트가 쓰는 공개값과 같다")
	void naver_client_id_default_matches_frontend() {
		assertThat(defaultOf("naver"))
				.as("프론트가 authorize에 쓰는 client_id와 달라지면 토큰 교환이 거부된다(OAUTH_COMMUNICATION_FAILED)")
				.isEqualTo(NAVER_PUBLIC_CLIENT_ID);
	}

	@Test
	@DisplayName("카카오 client-id 기본값이 프론트가 쓰는 공개값과 같다")
	void kakao_client_id_default_matches_frontend() {
		assertThat(defaultOf("kakao")).isEqualTo(KAKAO_PUBLIC_CLIENT_ID);
	}

	@Test
	@DisplayName("client-id 기본값은 비어 있지 않다 — 시크릿 미주입 환경에서도 프론트와 맞아야 한다")
	void client_id_defaults_are_not_blank() {
		assertThat(defaultOf("naver")).isNotBlank();
		assertThat(defaultOf("kakao")).isNotBlank();
	}

	/** application.yaml의 {@code app.oauth.<provider>.client-id} 플레이스홀더에서 기본값을 뽑는다. */
	@SuppressWarnings("unchecked")
	private static String defaultOf(String provider) {
		try (InputStream in = OAuthClientIdDefaultTest.class.getResourceAsStream("/application.yaml")) {
			// 프로파일별 문서가 --- 로 이어지므로 기본 프로파일(첫 문서)만 본다.
			Map<String, Object> root = (Map<String, Object>) new Yaml().loadAll(in).iterator().next();
			Map<String, Object> app = (Map<String, Object>) root.get("app");
			Map<String, Object> oauth = (Map<String, Object>) app.get("oauth");
			Map<String, Object> target = (Map<String, Object>) oauth.get(provider);
			String expression = String.valueOf(target.get("client-id"));
			Matcher matcher = Pattern.compile("^\\$\\{[A-Z_]+:(.*)}$").matcher(expression);
			assertThat(matcher.matches())
					.as("client-id는 ${ENV:기본값} 형태여야 한다: " + expression)
					.isTrue();
			return matcher.group(1);
		} catch (Exception e) {
			throw new IllegalStateException("application.yaml에서 " + provider + " client-id를 읽지 못했다", e);
		}
	}
}
