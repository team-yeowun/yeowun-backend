package modi.backend.interfaces.admin;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import modi.backend.application.admin.AdminExhibitionFacade;
import modi.backend.application.exhibition.ExhibitionFacade;
import modi.backend.application.admin.AdminExhibitionResult;
import modi.backend.interfaces.admin.dto.AdminExhibitionDto;
import modi.backend.support.response.ApiResponse;

/**
 * 관리자 전시 유지보수 API. `/api-admin/**` 게이트는 {@code AdminAuthInterceptor}가 담당.
 * 프론트가 쓰지 않는 내부 운영용이라 {@link Hidden}으로 Swagger 문서에서 제외하고 별도 ApiSpec도 두지 않는다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api-admin/v1/exhibitions")
public class AdminExhibitionV1Controller {

	private final AdminExhibitionFacade adminExhibitionFacade;
	private final ExhibitionFacade exhibitionFacade;

	/** 저장된 전시 설명을 재파싱해 HTML/워드프레스 마크업을 벗긴다(기존 수집분 정리, 멱등). */
	@PostMapping("/descriptions/reparse")
	public ApiResponse<AdminExhibitionResult.DescriptionReparse> reparseDescriptions() {
		return ApiResponse.success(adminExhibitionFacade.reparseDescriptions());
	}

	/** 전시 필드 수정(부분) — 넘긴 필드만 갱신하고, 실제로 바뀐 필드는 이력(exhibition_history)에 남는다. */
	@PutMapping("/{exhibitionId}")
	public ApiResponse<AdminExhibitionResult.Edited> editExhibition(
			@PathVariable Long exhibitionId,
			@RequestBody AdminExhibitionDto.EditRequest request) {
		return ApiResponse.success(adminExhibitionFacade.editExhibition(exhibitionId,
				request.title(), request.place(), request.price(), request.description()));
	}

	/**
	 * - 목록 캐시 즉시 재적재(수동 워밍)
	 *   - 무효화 방송이 유실됐을 때의 사후 복구 수단
	 *   - 자동 재발행을 두지 않았으므로 이것이 유일한 즉시 복구 수단
	 *   - {@code refresh}가 L2 갱신과 방송을 함께 다시 하므로 전 서버의 L1까지 정리됨
	 *
	 * - 멱등이라 눌러도 손해가 없음
	 *   - 덮어쓰기뿐이고 조회 쿼리 7건이 전부
	 */
	@PostMapping("/cache/warm")
	public ApiResponse<Object> warmListCaches() {
		exhibitionFacade.warmListCaches();
		return ApiResponse.success();
	}
}
