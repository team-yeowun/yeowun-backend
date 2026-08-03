package modi.backend.interfaces.common.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 커서 페이지네이션 공통 응답 봉투(공통 규약 §2). 목록 조회(전시·관심 전시·알림 등)가 공유한다.
 * 오프셋 {@code PageResponse}와 달리 무한 스크롤에서 페이지 밀림이 없다.
 *
 * @param content    이번 페이지 항목들
 * @param nextCursor 다음 페이지 조회용 opaque 커서. 마지막 페이지면 null.
 * @param hasNext    다음 페이지 존재 여부
 * @param totalCount 조건 기준 전체 건수("총 N개" UI용)
 */
public record CursorResponse<T>(
		@Schema(description = "이번 페이지 항목들") List<T> content,
		@Schema(description = "다음 페이지 조회용 opaque 커서. 마지막 페이지면 null.", nullable = true) String nextCursor,
		@Schema(description = "다음 페이지 존재 여부", example = "true") boolean hasNext,
		@Schema(description = "조건 기준 전체 건수", example = "56") long totalCount) {

	public static <T> CursorResponse<T> of(List<T> content, String nextCursor, boolean hasNext, long totalCount) {
		return new CursorResponse<>(content, nextCursor, hasNext, totalCount);
	}

	/** 항목이 없을 때(빈 페이지) — content [], nextCursor null, hasNext false. */
	public static <T> CursorResponse<T> empty() {
		return new CursorResponse<>(List.of(), null, false, 0);
	}
}
