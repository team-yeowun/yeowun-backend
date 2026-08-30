package modi.backend.ingestionv2.enrich.domain.genre;

import java.util.List;
import java.util.stream.Collectors;

import modi.backend.domain.exhibition.genre.GenreProvider;

/**
 * 장르 분류 결과.
 *
 * <ul>
 *   <li>keyword가 null이면 전 공급자 소진. 성공과 실패를 같은 타입으로 돌려받는다</li>
 *   <li>vendor는 성공한 공급자이고 failedAttempts는 그 앞에서 실패한 공급자들</li>
 *   <li>fallbackUsed는 실패한 시도가 하나라도 있었는지. 별도로 받지 않고 유도한다</li>
 * </ul>
 */
public record GenreResult(String keyword, GenreProvider vendor, String model, boolean fallbackUsed,
		List<Attempt> failedAttempts) {

	/** 실패한 시도 1회. 어느 공급자가 무엇 때문에 실패했는지. */
	public record Attempt(GenreProvider vendor, String reason) {
	}

	public static GenreResult classified(String keyword, GenreProvider vendor, String model,
			List<Attempt> failedAttempts) {
		return new GenreResult(keyword, vendor, model, !failedAttempts.isEmpty(), List.copyOf(failedAttempts));
	}

	public static GenreResult exhausted(List<Attempt> failedAttempts) {
		return new GenreResult(null, null, null, !failedAttempts.isEmpty(), List.copyOf(failedAttempts));
	}

	public boolean isClassified() {
		return keyword != null;
	}

	/** 하위 엔티티에 남길 마지막 시도 공급자. 성공했으면 성공한 쪽, 아니면 마지막 실패 쪽. */
	public String lastVendorName() {
		if (vendor != null) {
			return vendor.name();
		}
		if (failedAttempts.isEmpty()) {
			return null;
		}
		return failedAttempts.get(failedAttempts.size() - 1).vendor().name();
	}

	/** 하위 엔티티의 last_error에 남길 요약. 어느 공급자가 무엇 때문에 실패했는지가 한 줄에 담긴다. */
	public String failureSummary() {
		return failedAttempts.stream()
				.map(attempt -> attempt.vendor().name() + ": " + attempt.reason())
				.collect(Collectors.joining(" / "));
	}
}
