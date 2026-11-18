package modi.backend.ingestionv2.common.event;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID v7 - 앞 48비트가 밀리초 타임스탬프라 생성 순서대로 정렬되고, 나머지 74비트는 난수다.
 *
 * <ul>
 *   <li>Inbox의 UNIQUE(event_id) 인덱스에 새 값이 항상 끝쪽에 들어가 v4의 무작위 삽입보다 페이지 분할이 적다</li>
 *   <li>형식은 그대로 UUID라 컬럼·Stream 필드·{@link UUID#fromString} 검증을 바꾸지 않는다</li>
 *   <li>같은 밀리초 안의 순서는 보장하지 않는다 - 정렬 힌트일 뿐 유일성은 난수 74비트가 맡는다</li>
 * </ul>
 */
public final class UuidV7 {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long VERSION_7 = 0x7L << 12;
	private static final long RAND_A_MASK = 0x0FFFL;
	private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
	private static final long VARIANT_RFC = 0x8000_0000_0000_0000L;

	private UuidV7() {
	}

	public static UUID generate() {
		long unixMillis = System.currentTimeMillis();
		long mostSignificant = (unixMillis << 16) | VERSION_7 | (RANDOM.nextLong() & RAND_A_MASK);
		long leastSignificant = (RANDOM.nextLong() & RAND_B_MASK) | VARIANT_RFC;
		return new UUID(mostSignificant, leastSignificant);
	}

	/** 앞 48비트에 담긴 생성 시각(epoch millis). */
	public static long timestampMillis(UUID uuid) {
		return uuid.getMostSignificantBits() >>> 16;
	}
}
