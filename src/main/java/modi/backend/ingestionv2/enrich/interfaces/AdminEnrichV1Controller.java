package modi.backend.ingestionv2.enrich.interfaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.enrich.domain.EnrichCriteria;
import modi.backend.ingestionv2.enrich.domain.EnrichFacade;
import modi.backend.support.response.ApiResponse;

/**
 * 보강 관리자 콘솔.
 *
 * <ul>
 *   <li>파사드만 호출. 서비스를 직접 주입하지 않음</li>
 *   <li>Request를 Criteria로, Result를 Response로 옮기는 것이 이 계층의 일</li>
 *   <li>예외 처리 없음. 전역 핸들러가 ErrorCode로 매핑</li>
 * </ul>
 */
@Hidden
@RestController
@RequestMapping("/api-admin/v1/ingestion-enrichments")
@RequiredArgsConstructor
public class AdminEnrichV1Controller implements AdminEnrichV1ApiSpec {

	private final EnrichFacade enrichFacade;

	@Override
	@GetMapping("/failed")
	public ApiResponse<EnrichDto.FailedPageResponse> getFailedEnrichments(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success(EnrichDto.FailedPageResponse.from(
				enrichFacade.findFailed(new EnrichCriteria.FailedSearch(page, size))));
	}

	@Override
	@PostMapping("/{vendorKey}/reopen")
	public ApiResponse<EnrichDto.ReopenResponse> reopenEnrichment(@PathVariable String vendorKey) {
		return ApiResponse.success(EnrichDto.ReopenResponse.from(enrichFacade.reopen(vendorKey)));
	}
}
