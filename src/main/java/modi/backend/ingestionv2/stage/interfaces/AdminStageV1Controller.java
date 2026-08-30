package modi.backend.ingestionv2.stage.interfaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.stage.domain.StageFacade;
import modi.backend.support.response.ApiResponse;

/**
 * 관리자 스테이징 콘솔.
 *
 * <ul>
 *   <li>/api-admin 은 인터셉터가 관리자만 통과시키므로 컨트롤러는 조율만 담당</li>
 *   <li>유스케이스 출력을 그대로 내보내지 않고 응답 DTO 로 변환</li>
 *   <li>파사드만 호출하고 서비스를 직접 주입하지 않음</li>
 * </ul>
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api-admin/v1/ingestion-stagings")
public class AdminStageV1Controller implements AdminStageV1ApiSpec {

	private final StageFacade stageFacade;

	@Override
	@GetMapping("/failed")
	public ApiResponse<StageDto.FailedPageResponse> getFailedStagings(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success(StageDto.FailedPageResponse.from(stageFacade.findFailed(page, size)));
	}

	@Override
	@PostMapping("/{vendorKey}/reopen")
	public ApiResponse<StageDto.ReopenedResponse> reopenStaging(@PathVariable String vendorKey) {
		return ApiResponse.success(StageDto.ReopenedResponse.from(stageFacade.reopen(vendorKey)));
	}
}
