package modi.backend.domain.exhibition.ranking;

import java.time.LocalDate;

/**
 * - 랭킹 알고리즘 하나 = 이 인터페이스 구현 하나 (전략 패턴)
 *   - 어느 날짜 버킷을 어떤 가중치로 합칠지만 결정
 *   - Redis·키 문자열은 모름, 실행은 저장소 몫
 *
 * - 알고리즘을 바꾸거나 늘려도 저장소·Lua·서비스는 그대로
 *   - 교체: 설정의 banner-type만 바꿈
 *   - 추가: 구현 클래스 + {@link RankingType} 상수 + RankingConfig 빈 등록
 */
public interface RankingStrategy {

	/** 이 전략이 만드는 순위판 종류. 종류 하나에 전략 하나. */
	RankingType type();

	/** {@code today} 기준으로 합칠 날짜 버킷과 가중치. */
	RankingPlan plan(LocalDate today);
}
