package modi.backend.application.record;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.config.AiProperties;
import modi.backend.support.error.CoreException;

class AiRateLimiterTest {

	private AiRateLimiter limiter(long rateLimitSeconds) {
		return new AiRateLimiter(new AiProperties(null, null, null, null, rateLimitSeconds, null, null));
	}

	@Test
	@DisplayName("쿨다운 내 재호출은 AI_RATE_LIMITED로 막는다")
	void 쿨다운_내_재호출_차단() {
		AiRateLimiter limiter = limiter(10);
		assertThatCode(() -> limiter.check(1L)).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.check(1L)).isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("사용자별로 독립적으로 제한한다")
	void 사용자별_독립() {
		AiRateLimiter limiter = limiter(10);
		limiter.check(1L);
		assertThatCode(() -> limiter.check(2L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("간격 0이면 제한하지 않는다")
	void 간격0_비활성() {
		AiRateLimiter limiter = limiter(0);
		limiter.check(1L);
		assertThatCode(() -> limiter.check(1L)).doesNotThrowAnyException();
	}
}
