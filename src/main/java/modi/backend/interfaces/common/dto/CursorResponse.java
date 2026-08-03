package modi.backend.interfaces.common.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 커서 페이지네이션 공통 응답 봉투(공통 규약 §2). 목록 조회(전시·관심 전시·알림 등)가 공유한다.
 * 오프셋 {@code PageResponse}와 달리 무한 스크롤에서 페이지 밀림이 없다.
 *
 * <p><b>totalCount는 선택 항목이다.</b> 총 건수가 필요한 목록(관심 전시·알림)은 {@link #of}로 채우고,
 * 총 건수를 세지 않는 슬라이스(전시 목록)는 {@link #ofSlice}로 비운다 — 비면 응답에서 필드가 아예 빠진다
 * ({@code @JsonInclude(NON_NULL)}). {@code 0}을 채워 내보내면 프론트가 "총 0개"로 읽어 없는 것보다 나쁘다.
 *
 * <p><b>NON_NULL은 반드시 {@code totalCount} 필드에만 붙인다.</b> record 전체에 붙이면 마지막 페이지의
 * {@code nextCursor}(null)까지 사라져, {@code nextCursor === null}로 끝을 판정하는 클라이언트가 깨진다.
 *
 * @param content    이번 페이지 항목들
 * @param nextCursor 다음 페이지 조회용 opaque 커서. 마지막 페이지면 null.
 * @param hasNext    다음 페이지 존재 여부
 * @param totalCount 조건 기준 전체 건수("총 N개" UI용). 세지 않는 목록은 null이며 응답에서 생략된다.
 */
public record CursorResponse<T>(
		@Schema(description = "이번 페이지 항목들") List<T> content,
		@Schema(description = "다음 페이지 조회용 opaque 커서. 마지막 페이지면 null.", nullable = true) String nextCursor,
		@Schema(description = "다음 페이지 존재 여부", example = "true") boolean hasNext,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "조건 기준 전체 건수. 총 건수를 세지 않는 목록(전시)은 응답에서 생략된다.",
				example = "56", nullable = true) Long totalCount) {

	/** 전체 건수를 함께 주는 목록 — 관심 전시·알림. */
	public static <T> CursorResponse<T> of(List<T> content, String nextCursor, boolean hasNext, long totalCount) {
		return new CursorResponse<>(content, nextCursor, hasNext, totalCount);
	}

	/**
	 * 총 건수를 세지 않는 슬라이스 — 전시 목록. {@code totalCount}가 응답에서 빠진다.
	 * 전시의 총 건수는 별도 엔드포인트({@code GET /api/v1/exhibitions/count})가 준다.
	 */
	public static <T> CursorResponse<T> ofSlice(List<T> content, String nextCursor, boolean hasNext) {
		return new CursorResponse<>(content, nextCursor, hasNext, null);
	}

	/** 항목이 없을 때(빈 페이지) — content [], nextCursor null, hasNext false. */
	public static <T> CursorResponse<T> empty() {
		return new CursorResponse<>(List.of(), null, false, 0L);
	}
}
