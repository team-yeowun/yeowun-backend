package modi.backend.ingestionv2.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UUID v7 event_id")
class UuidV7Test {

	@Test
	@DisplayName("RFC 9562 버전 7·IETF 변형 비트를 가진 표준 UUID 문자열을 만든다")
	void 버전7_UUID를_만든다() {
		UUID uuid = UuidV7.generate();

		assertThat(uuid.version()).isEqualTo(7);
		assertThat(uuid.variant()).isEqualTo(2);
		assertThat(UUID.fromString(uuid.toString())).isEqualTo(uuid);
	}

	@Test
	@DisplayName("앞 48비트가 생성 시각이라 나중에 만든 값이 문자열 정렬에서도 뒤에 온다")
	void 생성_순서대로_정렬된다() throws InterruptedException {
		UUID earlier = UuidV7.generate();
		long before = System.currentTimeMillis();
		Thread.sleep(2);
		UUID later = UuidV7.generate();

		assertThat(UuidV7.timestampMillis(earlier)).isLessThanOrEqualTo(before);
		assertThat(UuidV7.timestampMillis(later)).isGreaterThan(UuidV7.timestampMillis(earlier));
		assertThat(later.toString()).isGreaterThan(earlier.toString());
	}

	@Test
	@DisplayName("같은 밀리초에 여러 개를 만들어도 난수 74비트로 서로 다르다")
	void 같은_밀리초에서도_충돌하지_않는다() {
		Set<UUID> generated = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			generated.add(UuidV7.generate());
		}

		assertThat(generated).hasSize(10_000);
	}
}
