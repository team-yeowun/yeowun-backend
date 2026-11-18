package modi.backend.domain.exhibition.ranking;

/** 순위판 한 칸. 순위는 1부터. */
public record RankingEntry(long exhibitionId, long rank, double score) {

	public RankingEntry {
		if (rank < 1) {
			throw new IllegalArgumentException("순위는 1부터 시작함: " + rank);
		}
	}
}
