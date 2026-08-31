package modi.backend.ingestionv2.enrich.interfaces;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import modi.backend.support.response.ApiResponse;

/**
 * 보강 관리자 API 스펙.
 *
 * <ul>
 *   <li>Swagger 어노테이션만 소유. MVC 어노테이션은 컨트롤러</li>
 *   <li>인증 파라미터 없음. /api-admin 게이트는 인터셉터가 담당</li>
 * </ul>
 */
@Tag(name = "Admin Enrich V1", description = "수집 파이프라인 보강 관리. 재시도 상한을 소진한 건을 스텝별로 확인하고 다시 실행한다.")
public interface AdminEnrichV1ApiSpec {

	@Operation(summary = "실패한 보강 건 조회",
			description = "재시도 상한을 소진해 자동 회생을 중단한 보강을 식별자 역순으로 조회한다. "
					+ "항목마다 상세와 장르와 개장 시간 세 스텝의 상태와 시도 횟수와 마지막 오류가 함께 실리며, "
					+ "stepCounts 에는 현재 페이지가 아니라 실패 전체에 대한 스텝별 건수가 담긴다.")
	ApiResponse<EnrichDto.FailedPageResponse> getFailedEnrichments(
			@Parameter(description = "0부터 시작하는 페이지 번호", example = "0") int page,
			@Parameter(description = "페이지 크기, 최대 100", example = "20") int size);

	@Operation(summary = "실패한 보강 건 재시도",
			description = "FAILED 인 보강의 실패한 스텝만 다시 열고 시도 횟수를 0으로 되돌린다. "
					+ "실제 실행은 함께 적재된 실행 이벤트를 배달 계층이 전달할 때 일어나므로, "
					+ "응답이 돌아온 시점에 보강이 끝난 것은 아니다.")
	ApiResponse<EnrichDto.ReopenResponse> reopenEnrichment(
			@Parameter(description = "문화포털 원천 키", example = "cs_2024_000123") String vendorKey);
}
