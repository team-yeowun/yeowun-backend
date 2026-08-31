package modi.backend.ingestionv2.collect.interfaces;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import modi.backend.ingestionv2.collect.domain.CollectResult;

/**
 * 수집 관리자 API 입출력.
 *
 * <ul>
 *   <li>도메인별 외곽 클래스에 중첩 record (파일 1개당 1 record 금지 규칙)</li>
 *   <li>형식 검증은 여기, 도메인 불변식은 엔티티와 값 객체 (같은 규칙 양쪽 중복 금지)</li>
 *   <li>Result를 그대로 노출하지 않고 Response로 옮김 (파사드는 이 타입을 모름)</li>
 * </ul>
 */
public final class CollectDto {

	private CollectDto() {
	}

	/** 실행할 회차 지정. 아직 선점되지 않은 날짜만 실제로 실행된다. */
	public record BatchRunRequest(
			@Schema(description = "실행할 회차 날짜. 한국 시간 기준", example = "2026-08-25")
			@NotNull
			@PastOrPresent
			@JsonFormat(pattern = "yyyy-MM-dd")
			LocalDate batchDate) {
	}

	/**
	 * 회차 실행 결과.
	 *
	 * <ul>
	 *   <li>claimed=false 는 오류가 아니라 이미 선점된 회차라는 사실</li>
	 *   <li>skipped 는 이미 확정된 전시를 건너뛴 멱등 동작</li>
	 *   <li>failed 는 전시 1건짜리 트랜잭션이 롤백된 건수</li>
	 * </ul>
	 */
	public record BatchRunResponse(boolean claimed, int collected, int skipped, int failed) {

		public static BatchRunResponse from(CollectResult.Batch result) {
			return new BatchRunResponse(result.claimed(), result.collected(), result.skipped(), result.failed());
		}
	}
}
