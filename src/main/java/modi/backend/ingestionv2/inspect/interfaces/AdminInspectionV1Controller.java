package modi.backend.ingestionv2.inspect.interfaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.inspect.domain.InspectCriteria;
import modi.backend.ingestionv2.inspect.domain.InspectFacade;
import modi.backend.ingestionv2.inspect.domain.RejectReason;
import modi.backend.support.response.ApiResponse;

/**
 * 관리자 점검 API.
 *
 * <ul>
 *   <li>프론트가 쓰지 않는 내부 운영용이라 Swagger 문서에서 제외하고 ApiSpec을 두지 않음</li>
 *   <li>인증 게이트는 /api-admin 인터셉터가 담당</li>
 *   <li>파사드만 호출하고 값 변환만 수행 (판단 없음)</li>
 * </ul>
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api-admin/v1/inspections")
public class AdminInspectionV1Controller {

	private final InspectFacade inspectFacade;

	/** 반려 목록. 사유를 넘기면 그 사유가 붙은 건만 추린다. */
	@GetMapping("/rejections")
	public ApiResponse<InspectDto.RejectedPageResponse> findRejections(
			@RequestParam(required = false) RejectReason reason,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success(InspectDto.RejectedPageResponse.from(
				inspectFacade.findRejected(InspectCriteria.RejectedSearch.of(reason, page, size))));
	}

	/** 단건 진단. 점검 결과와 원장 단면을 함께 돌려준다. */
	@GetMapping("/{vendorKey}")
	public ApiResponse<InspectDto.DetailResponse> findOne(@PathVariable String vendorKey) {
		return ApiResponse.success(InspectDto.DetailResponse.from(inspectFacade.findDetail(vendorKey)));
	}

	/** 재점검. 반려된 전시만 다시 판정하고 그 결과를 돌려준다. */
	@PostMapping("/{vendorKey}/reinspections")
	public ApiResponse<InspectDto.DetailResponse> reinspect(@PathVariable String vendorKey) {
		inspectFacade.reinspect(vendorKey);
		return ApiResponse.success(InspectDto.DetailResponse.from(inspectFacade.findDetail(vendorKey)));
	}
}
