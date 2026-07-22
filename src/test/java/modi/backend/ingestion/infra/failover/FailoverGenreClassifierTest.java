package modi.backend.ingestion.infra.failover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationException;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreProvider;
import modi.backend.domain.exhibition.genre.GenreResult;

/**
 * FailoverGenreClassifier 단위 검증(ADR-11) — 1차 실패 시 2차 전환, 전 공급자 실패 시 예외.
 * 각 공급자는 한 번씩만 부른다(호출 내 재시도·서킷브레이커는 걷어냈다 — durable 재시도는 아웃박스).
 */
class FailoverGenreClassifierTest {

	private static final GenreClassification INPUT = new GenreClassification("전시", null, null, null, null, null);

	private static GenreResult gemini() {
		return GenreResult.ai("사진", GenreProvider.GEMINI, "gemini-2.5-flash");
	}

	private static GenreResult claude() {
		return GenreResult.ai("공예", GenreProvider.CLAUDE, "claude-haiku-4-5-20251001");
	}

	private static GenreClassifier failing(AtomicInteger calls) {
		return input -> {
			calls.incrementAndGet();
			throw new GenreClassificationException("1차 장애");
		};
	}

	@Test
	@DisplayName("1차가 성공하면 2차를 호출하지 않는다")
	void 일차성공_이차미호출() {
		AtomicInteger secondaryCalls = new AtomicInteger();
		GenreClassifier secondary = input -> {
			secondaryCalls.incrementAndGet();
			return claude();
		};
		FailoverGenreClassifier chain = new FailoverGenreClassifier(input -> gemini(), secondary);

		GenreResult result = chain.classify(INPUT);

		assertThat(result.provider()).isEqualTo(GenreProvider.GEMINI);
		assertThat(secondaryCalls.get()).isZero();
	}

	@Test
	@DisplayName("1차 실패 시 2차로 전환하고, 계보엔 실제 분류자(CLAUDE)가 남는다")
	void 일차실패_이차전환() {
		FailoverGenreClassifier chain = new FailoverGenreClassifier(failing(new AtomicInteger()), input -> claude());

		GenreResult result = chain.classify(INPUT);

		assertThat(result.provider()).isEqualTo(GenreProvider.CLAUDE);
	}

	@Test
	@DisplayName("1차는 재시도 없이 한 번만 호출한다(호출 내 재시도는 이 계층의 책임이 아니다)")
	void 일차_단일시도() {
		AtomicInteger primaryCalls = new AtomicInteger();
		FailoverGenreClassifier chain = new FailoverGenreClassifier(failing(primaryCalls), input -> claude());

		chain.classify(INPUT);

		assertThat(primaryCalls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("전 공급자 실패면 분류 실패 예외를 던진다(1차 실패는 suppressed로 보존 — 아웃박스가 durable 재시도)")
	void 전공급자실패_예외() {
		GenreClassifier failingSecondary = input -> {
			throw new GenreClassificationException("2차도 장애");
		};
		FailoverGenreClassifier chain = new FailoverGenreClassifier(failing(new AtomicInteger()), failingSecondary);

		assertThatThrownBy(() -> chain.classify(INPUT))
				.isInstanceOf(GenreClassificationException.class)
				.hasMessageContaining("전 공급자 실패")
				.satisfies(e -> assertThat(e.getSuppressed()).isNotEmpty());
	}
}
