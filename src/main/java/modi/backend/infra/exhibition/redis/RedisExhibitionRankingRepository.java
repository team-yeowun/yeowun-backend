package modi.backend.infra.exhibition.redis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import modi.backend.domain.exhibition.ranking.BoardIncrement;
import modi.backend.domain.exhibition.ranking.ExhibitionRankingRepository;
import modi.backend.domain.exhibition.ranking.RankingEntry;
import modi.backend.domain.exhibition.ranking.RankingPlan;
import modi.backend.domain.exhibition.ranking.RankingTieBreak;
import modi.backend.domain.exhibition.ranking.RankingType;
import modi.backend.domain.exhibition.ranking.WeightedBucket;

/**
 * - 전시 랭킹의 Redis 어댑터 (Sorted Set)
 *   - 날짜 버킷이 원본이라 TTL 없이 둠 → 운영 Redis(volatile-lru)에서 축출 대상이 아님
 *   - 보존 기간 밖 버킷은 {@link #purgeBefore}가 직접 지움
 *
 * - 알고리즘을 모름
 *   - 기록·재계산 Lua 둘 다 버킷 수와 가중치를 인자로 받아, 3일·7일·지수 감쇠가 같은 스크립트로 돎
 *
 * - 앱 2대에서 락이 필요 없는 이유
 *   - 기록: Lua 한 번 안의 ZINCRBY라 동시에 와도 증가분이 사라지지 않음
 *   - 재계산: 같은 원본·같은 후보에서 다시 만들어 덮어써 몇 번 돌아도 결과가 같음
 *   - 재계산 도중 기록: 스크립트가 통째로 실행돼 기록은 앞이나 뒤에만 끼어듦
 *     - 앞이면 원본에 들어가 합산되고, 뒤면 새 순위판에 더해짐
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisExhibitionRankingRepository implements ExhibitionRankingRepository {

	/** 보존 경계 앞으로 지워 볼 날 수. 앱이 며칠 꺼져 있다 켜져도 남은 버킷을 치울 수 있게. */
	private static final int PURGE_LOOKBACK_DAYS = 31;

	/**
	 * - 기록
	 *   - KEYS[1] = 오늘 버킷, KEYS[2] = 후보 집합, KEYS[3..] = 활성 순위판
	 *   - ARGV[1] = 전시 id, ARGV[2..] = 순위판별 오늘 가중치 (KEYS[i]의 가중치는 ARGV[i - 1])
	 *
	 * - 후보가 아닌 전시(종료·CUSTOM·날짜 미상)는 원본에만 쌓이고 순위판에는 오르지 않음
	 */
	private static final RedisScript<Long> RECORD = RedisScript.of("""
			redis.call('ZINCRBY', KEYS[1], 1, ARGV[1])
			if redis.call('ZSCORE', KEYS[2], ARGV[1]) then
				for i = 3, #KEYS do
					redis.call('ZINCRBY', KEYS[i], ARGV[i - 1], ARGV[1])
				end
			end
			return 1
			""", Long.class);

	/**
	 * - 재계산
	 *   - KEYS[1] = 후보 집합, KEYS[2] = 순위판, KEYS[3] = 중간 결과, KEYS[4..] = 날짜 버킷
	 *   - ARGV[1] = 버킷 수 n, ARGV[2..n+1] = 버킷 가중치, 이후 (전시 id, 동점 점수) 쌍
	 *
	 * - 후보 교체 → 버킷 가중 합산(+ 동점 점수) → 후보만 남겨 순위판 덮어쓰기
	 *   - 후보 집합을 가중치 1로 함께 합쳐 동점 점수가 더해짐
	 *   - ZINTERSTORE 가중치 1, 0 → 점수는 합산 결과 그대로, 후보가 아닌 전시만 빠짐
	 *   - 결과가 비면 ZINTERSTORE가 순위판 키를 지움 (진행 중 전시가 없는 날)
	 */
	private static final RedisScript<Long> REBUILD = RedisScript.of("""
			local n = tonumber(ARGV[1])
			redis.call('DEL', KEYS[1])
			for i = n + 2, #ARGV, 2 do
				redis.call('ZADD', KEYS[1], ARGV[i + 1], ARGV[i])
			end
			local args = { KEYS[3], n + 1 }
			for i = 1, n do
				args[#args + 1] = KEYS[i + 3]
			end
			args[#args + 1] = KEYS[1]
			args[#args + 1] = 'WEIGHTS'
			for i = 1, n do
				args[#args + 1] = ARGV[i + 1]
			end
			args[#args + 1] = 1
			redis.call('ZUNIONSTORE', unpack(args))
			redis.call('ZINTERSTORE', KEYS[2], 2, KEYS[3], KEYS[1], 'WEIGHTS', 1, 0)
			redis.call('DEL', KEYS[3])
			return redis.call('ZCARD', KEYS[2])
			""", Long.class);

	private final StringRedisTemplate redisTemplate;

	@Override
	public void recordView(long exhibitionId, LocalDate today, List<BoardIncrement> increments) {
		List<String> keys = new ArrayList<>(increments.size() + 2);
		keys.add(RankingRedisKeys.bucket(today));
		keys.add(RankingRedisKeys.candidates());
		List<String> args = new ArrayList<>(increments.size() + 1);
		args.add(String.valueOf(exhibitionId));
		for (BoardIncrement increment : increments) {
			keys.add(RankingRedisKeys.board(increment.type()));
			args.add(number(increment.weight()));
		}
		try {
			redisTemplate.execute(RECORD, keys, args.toArray());
		} catch (Exception e) {
			// 랭킹은 부가 값이다 — Redis가 죽었다고 상세 응답까지 죽일 이유가 없다.
			log.warn("랭킹 기록 실패, 이번 조회는 순위에 세지 않는다: exhibitionId={}", exhibitionId, e);
		}
	}

	@Override
	public long rebuild(RankingPlan plan, RankingTieBreak tieBreak) {
		List<WeightedBucket> buckets = plan.buckets();
		List<String> keys = new ArrayList<>(buckets.size() + 3);
		keys.add(RankingRedisKeys.candidates());
		keys.add(RankingRedisKeys.board(plan.type()));
		keys.add(RankingRedisKeys.scratch(plan.type()));
		List<String> args = new ArrayList<>(1 + buckets.size() + tieBreak.scores().size() * 2);
		args.add(String.valueOf(buckets.size()));
		for (WeightedBucket bucket : buckets) {
			keys.add(RankingRedisKeys.bucket(bucket.day()));
			args.add(number(bucket.weight()));
		}
		tieBreak.scores().forEach((exhibitionId, score) -> {
			args.add(String.valueOf(exhibitionId));
			args.add(number(score));
		});
		Long size = redisTemplate.execute(REBUILD, keys, args.toArray());
		return size == null ? 0 : size;
	}

	@Override
	public void purgeBefore(LocalDate keepFrom) {
		List<String> stale = IntStream.rangeClosed(1, PURGE_LOOKBACK_DAYS)
				.mapToObj(daysBefore -> RankingRedisKeys.bucket(keepFrom.minusDays(daysBefore)))
				.toList();
		redisTemplate.delete(stale);
	}

	@Override
	public List<RankingEntry> top(RankingType type, int size) {
		if (size < 1) {
			return List.of();
		}
		Set<ZSetOperations.TypedTuple<String>> tuples =
				redisTemplate.opsForZSet().reverseRangeWithScores(RankingRedisKeys.board(type), 0, size - 1);
		if (tuples == null) {
			return List.of();
		}
		List<RankingEntry> entries = new ArrayList<>(tuples.size());
		long rank = 1;
		for (ZSetOperations.TypedTuple<String> tuple : tuples) {
			entries.add(new RankingEntry(Long.parseLong(tuple.getValue()), rank++, scoreOf(tuple.getScore())));
		}
		return entries;
	}

	@Override
	public Optional<RankingEntry> find(RankingType type, long exhibitionId) {
		String board = RankingRedisKeys.board(type);
		String member = String.valueOf(exhibitionId);
		Long index = redisTemplate.opsForZSet().reverseRank(board, member);
		if (index == null) {
			return Optional.empty();
		}
		Double score = redisTemplate.opsForZSet().score(board, member);
		return Optional.of(new RankingEntry(exhibitionId, index + 1, scoreOf(score)));
	}

	@Override
	public long count(RankingType type) {
		Long size = redisTemplate.opsForZSet().zCard(RankingRedisKeys.board(type));
		return size == null ? 0 : size;
	}

	/** Redis가 읽는 숫자 표기. 지수 표기(1.0E-5)를 피해 그대로 넘김. */
	private static String number(double value) {
		return BigDecimal.valueOf(value).toPlainString();
	}

	private static double scoreOf(Double score) {
		return score == null ? 0 : score;
	}
}
