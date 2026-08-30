package modi.backend.ingestionv2.stage.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 스테이징 유스케이스 출력.
 *
 * <ul>
 *   <li>코어 애그리거트를 통째로 넘기지 않고 전시 id 만 전달</li>
 *   <li>같은 이벤트가 두 번 도착했을 때의 결과를 호출자가 구분할 수 있도록 결과 종류를 함께 반환</li>
 * </ul>
 */
public final class StageResult {

	private StageResult() {
	}

	/** 반영 결과의 종류. */
	public enum Outcome {

		/** 이번 호출이 코어에 등록했다. */
		REGISTERED,

		/** 이미 등록되어 있어 아무것도 다시 하지 않았다. */
		ALREADY_STAGED,

		/** 재시도 상한을 소진해 관리자 대기열로 넘어간 건이라 진행하지 않았다. */
		ABANDONED
	}

	/** 반영 결과. 등록되지 않은 경우 전시 id 는 비어 있다. */
	public record Staged(String vendorKey, Long exhibitionId, Outcome outcome) {

		public static Staged registered(String vendorKey, long exhibitionId) {
			return new Staged(vendorKey, exhibitionId, Outcome.REGISTERED);
		}

		public static Staged alreadyStaged(String vendorKey, Long exhibitionId) {
			return new Staged(vendorKey, exhibitionId, Outcome.ALREADY_STAGED);
		}

		public static Staged abandoned(String vendorKey) {
			return new Staged(vendorKey, null, Outcome.ABANDONED);
		}
	}

	/** 관리자 수동 재시도 결과. */
	public record Reopened(String vendorKey) {
	}

	/** 관리자 실패 목록 항목. 애그리거트를 그대로 내보내지 않고 화면에 필요한 값만 담는다. */
	public record Failed(String vendorKey, int attempts, String lastError, LocalDateTime updatedAt) {

		public static Failed from(Staging staging) {
			return new Failed(staging.getVendorKey(), staging.getAttempts(), staging.getLastError(),
					staging.getUpdatedAt());
		}
	}

	/** 실패 목록 한 페이지. 오프셋 페이지네이션이라 전체 건수를 함께 돌려준다. */
	public record FailedPage(List<Failed> items, int page, int size, long totalCount) {
	}
}
