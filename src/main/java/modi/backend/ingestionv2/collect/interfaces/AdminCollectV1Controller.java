package modi.backend.ingestionv2.collect.interfaces;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.collect.domain.CollectCriteria;
import modi.backend.ingestionv2.collect.domain.CollectFacade;
import modi.backend.ingestionv2.collect.domain.CollectResult;
import modi.backend.support.response.ApiResponse;

/**
 * 수집 관리자 컨트롤러.
 *
 * <ul>
 *   <li>파사드만 호출 (서비스 직주입 금지)</li>
 *   <li>Request를 Criteria로, Result를 Response로 옮기는 것이 이 계층의 일</li>
 *   <li>예외 처리 없음 (전역 핸들러가 ErrorCode로 매핑)</li>
 * </ul>
 */
@Hidden
@RestController
@RequestMapping("/api-admin/v1/ingestion")
@RequiredArgsConstructor
public class AdminCollectV1Controller implements AdminCollectV1ApiSpec {

	private final CollectFacade collectFacade;

	@Override
	@PostMapping("/collect-batches")
	public ApiResponse<CollectDto.BatchRunResponse> run(@Valid @RequestBody CollectDto.BatchRunRequest request) {
		CollectResult.Batch result = collectFacade.collect(CollectCriteria.Batch.of(request.batchDate()));
		return ApiResponse.success(CollectDto.BatchRunResponse.from(result));
	}
}
