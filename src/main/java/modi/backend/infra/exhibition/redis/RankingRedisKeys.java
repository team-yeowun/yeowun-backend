package modi.backend.infra.exhibition.redis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import modi.backend.domain.exhibition.ranking.RankingType;

/**
 * - 랭킹 Redis 키 이름 (도메인은 "날짜 버킷 · 순위판"만 말하고 문자열은 여기서만 만듦)
 *
 * - 모든 키에 같은 해시 태그 {@code {exh-rank}}
 *   - Redis Cluster에서도 한 슬롯에 모여 여러 키를 쓰는 Lua·다중 키 DEL이 그대로 동작
 *   - 조회수 누산기의 RENAME이 남긴 CROSSSLOT 과제를 반복하지 않음
 *
 * - 원본(view)과 파생(board · candidates)을 이름에서 구분
 */
final class RankingRedisKeys {

	private static final String PREFIX = "ranking:{exh-rank}:";

	private RankingRedisKeys() {
	}

	/** 날짜 버킷(원본): 그날 전시별 조회수. */
	static String bucket(LocalDate day) {
		return PREFIX + "view:" + day.format(DateTimeFormatter.BASIC_ISO_DATE);
	}

	/** 순위판(파생). */
	static String board(RankingType type) {
		return PREFIX + "board:" + type.name().toLowerCase(Locale.ROOT);
	}

	/** 후보 집합(파생): 진행 중 전시와 동점 점수. */
	static String candidates() {
		return PREFIX + "candidates";
	}

	/** 재계산 중간 결과. 스크립트 안에서 만들고 지움. */
	static String scratch(RankingType type) {
		return PREFIX + "tmp:" + type.name().toLowerCase(Locale.ROOT);
	}
}
