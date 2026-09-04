package modi.backend.ingestionv2.common.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 회수 자격 규칙 - 지터가 시간축의 간격을 지키면서 항목 사이만 흩는지 고정한다. */
@DisplayName("회수 백오프 정책")
class ReclaimBackoffPolicyTest {

	private static final Duration BASE = Duration.ofSeconds(1);
	private static final Duration MAX = Duration.ofSeconds(16);

	private static ReclaimBackoffPolicy fullJitter() {
		return new ReclaimBackoffPolicy(ReclaimBackoff.EXPONENTIAL, ReclaimJitter.FULL, BASE, MAX);
	}

	@Nested
	@DisplayName("지터 임계치의 안정성")
	class Stability {

		@Test
		@DisplayName("같은 항목의 같은 전달 횟수는 몇 번을 물어도 같은 임계치를 준다")
		void 같은_입력은_같은_임계치() {
			// given 자격 판정은 회수 틱마다 반복 호출된다
			ReclaimBackoffPolicy policy = fullJitter();

			// when 같은 항목을 100번 물어본다
			Set<Duration> answers = new HashSet<>();
			for (int i = 0; i < 100; i++) {
				answers.add(policy.thresholdFor(4, 12345L));
			}

			// then 답이 하나뿐이다
			assertThat(answers)
					.as("호출마다 값이 달라지면 여러 번 뽑은 값 중 최솟값이 사실상 임계치가 되어 지수 지연이 base 로 무너진다")
					.hasSize(1);
		}

		@Test
		@DisplayName("판정을 반복해도 임계치 이전에는 회수 대상이 되지 않는다")
		void 임계치_이전에는_자격이_없다() {
			// given 임계치가 base 보다 큰 항목을 하나 고른다
			ReclaimBackoffPolicy policy = fullJitter();
			long jitterKey = jitterKeyWithThresholdAbove(policy, BASE.plusMillis(500));
			Duration threshold = policy.thresholdFor(4, jitterKey);
			Duration justBefore = threshold.minusMillis(1);

			// when 100ms 폴링을 흉내 내어 같은 경과 시간으로 반복 판정한다
			boolean eligibleOnce = false;
			for (int i = 0; i < 200; i++) {
				eligibleOnce |= policy.eligible(justBefore, 4, jitterKey);
			}

			// then 한 번도 자격을 얻지 못한다
			assertThat(eligibleOnce)
					.as("반복 판정만으로 임계치가 앞당겨지면 백오프를 켜고도 고정 간격으로 돈다")
					.isFalse();
			assertThat(policy.eligible(threshold, 4, jitterKey)).isTrue();
		}

		private long jitterKeyWithThresholdAbove(ReclaimBackoffPolicy policy, Duration floor) {
			for (long key = 1; key < 10_000; key++) {
				if (policy.thresholdFor(4, key).compareTo(floor) > 0) {
					return key;
				}
			}
			throw new IllegalStateException("지터가 전혀 흩어지지 않는다");
		}
	}

	@Nested
	@DisplayName("지터의 분산")
	class Spread {

		@Test
		@DisplayName("항목이 다르면 임계치가 갈려 같은 시각에 실패한 무리가 흩어진다")
		void 항목마다_임계치가_다르다() {
			// given
			ReclaimBackoffPolicy policy = fullJitter();

			// when 항목 500개의 임계치를 모은다
			Set<Duration> thresholds = new HashSet<>();
			for (long key = 0; key < 500; key++) {
				thresholds.add(policy.thresholdFor(5, key));
			}

			// then 값이 충분히 갈린다
			assertThat(thresholds.size())
					.as("전부 같은 값이면 지터가 없는 것과 같아 봉우리가 그대로 선다")
					.isGreaterThan(100);
		}

		@Test
		@DisplayName("임계치는 base 이상이고 그 전달 횟수의 지수 지연 이하다")
		void 임계치는_구간_안에_있다() {
			// given
			ReclaimBackoffPolicy policy = fullJitter();
			ReclaimBackoffPolicy noJitter =
					new ReclaimBackoffPolicy(ReclaimBackoff.EXPONENTIAL, ReclaimJitter.NONE, BASE, MAX);

			// when · then
			for (long deliveryCount = 1; deliveryCount <= 8; deliveryCount++) {
				Duration ceiling = noJitter.thresholdFor(deliveryCount, 0L);
				for (long key = 0; key < 200; key++) {
					Duration threshold = policy.thresholdFor(deliveryCount, key);
					assertThat(threshold)
							.as("XCLAIM 가드가 base 를 최솟값으로 받으므로 base 미만이면 요청하고도 못 받는다")
							.isGreaterThanOrEqualTo(BASE)
							.isLessThanOrEqualTo(ceiling);
				}
			}
		}
	}

	@Nested
	@DisplayName("지수 단계")
	class Exponential {

		@Test
		@DisplayName("전달 횟수만큼 배로 늘고 상한에서 멈춘다")
		void 지수는_상한에서_멈춘다() {
			// given
			ReclaimBackoffPolicy policy =
					new ReclaimBackoffPolicy(ReclaimBackoff.EXPONENTIAL, ReclaimJitter.NONE, BASE, MAX);

			// when · then
			assertThat(policy.thresholdFor(1, 0L)).isEqualTo(Duration.ofSeconds(1));
			assertThat(policy.thresholdFor(2, 0L)).isEqualTo(Duration.ofSeconds(2));
			assertThat(policy.thresholdFor(3, 0L)).isEqualTo(Duration.ofSeconds(4));
			assertThat(policy.thresholdFor(4, 0L)).isEqualTo(Duration.ofSeconds(8));
			assertThat(policy.thresholdFor(5, 0L)).isEqualTo(MAX);
			assertThat(policy.thresholdFor(50, 0L)).isEqualTo(MAX);
		}

		@Test
		@DisplayName("고정 간격은 전달 횟수와 무관하게 base 를 준다")
		void 고정_간격은_늘지_않는다() {
			// given
			ReclaimBackoffPolicy policy =
					new ReclaimBackoffPolicy(ReclaimBackoff.NONE, ReclaimJitter.NONE, BASE, MAX);

			// when · then
			assertThat(policy.thresholdFor(1, 0L)).isEqualTo(BASE);
			assertThat(policy.thresholdFor(30, 0L)).isEqualTo(BASE);
		}
	}
}
