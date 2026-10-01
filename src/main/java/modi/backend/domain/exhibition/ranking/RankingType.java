package modi.backend.domain.exhibition.ranking;

/**
 * - 순위판 종류 = 랭킹 알고리즘 하나
 *   - 계산 규칙은 같은 종류를 선언한 {@link RankingStrategy} 구현이 가짐
 *   - 설정값(active-types · banner-type)과 Redis 순위판 키 이름에 이 이름이 그대로 쓰임
 *
 * - 새 알고리즘 추가 = 상수 하나 + 전략 구현 하나 + 빈 등록
 */
public enum RankingType {

	/** 최근 3일 + 창 밖 하루 10%. 홈 배너 기본값. */
	RECENT_3D,

	/** 오늘 하루. */
	DAILY,

	/** 최근 7일 동일 가중. */
	WEEKLY,

	/** 반감기 1일 지수 감쇠(7일). */
	TRENDING
}
