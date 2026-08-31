package modi.backend.ingestionv2.stage.interfaces;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import modi.backend.support.response.ApiResponse;

/**
 * 스테이징 관리자 API 스펙.
 *
 * <ul>
 *   <li>Swagger 어노테이션만 소유 (MVC 어노테이션은 컨트롤러)</li>
 *   <li>인증 파라미터 없음 (/api-admin 게이트는 인터셉터가 담당)</li>
 * </ul>
 */
@Tag(name = "Admin Ingestion Staging",
		description = "수집 파이프라인 스테이징 관리. 코어 반영에 실패한 건을 확인하고 다시 대기 상태로 되돌린다.")
public interface AdminStageV1ApiSpec {

	@Operation(summary = "실패한 승격 건 조회",
			description = "재시도 상한을 소진해 자동 회생을 중단한 건을 마지막 실패 시각 내림차순으로 조회한다.")
	ApiResponse<StageDto.FailedPageResponse> getFailedStagings(
			@Parameter(description = "0부터 시작하는 페이지 번호", example = "0") int page,
			@Parameter(description = "페이지 크기, 최대 100", example = "20") int size);

	@Operation(summary = "실패한 승격 건 재시도",
			description = "FAILED 인 건을 PENDING 으로 되돌리고 시도 횟수를 0으로 초기화한다. "
					+ "실제 반영은 격리된 이벤트를 배달 계층이 재전달할 때 일어난다.")
	ApiResponse<StageDto.ReopenedResponse> reopenStaging(
			@Parameter(description = "문화포털 원천 키", example = "cs_2024_000123") String vendorKey);
}
