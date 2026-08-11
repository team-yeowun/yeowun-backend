package modi.backend.interfaces.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import modi.backend.application.admin.AdminCacheFacade;
import modi.backend.application.admin.AdminCacheResult;
import modi.backend.support.response.ApiResponse;

/**
 * 관리자 캐시 현황. {@code /api-admin/**}는 {@code AdminAuthInterceptor}가 관리자만 통과시키므로 컨트롤러는 조율만 한다.
 * 내부 콘솔이라 application Result를 응답으로 직접 반환한다(기존 admin 경로와 같은 단순화).
 *
 * <p>수동 워밍은 여기 두지 않는다 — 이미 {@code POST /api-admin/v1/exhibitions/cache/warm}에 있다.
 * 같은 일을 하는 문을 둘로 만들면 나중에 한쪽만 바뀐다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api-admin/v1/cache")
public class AdminCacheV1Controller implements AdminCacheV1ApiSpec {

	private final AdminCacheFacade adminCacheFacade;

	@Override
	@GetMapping("/stats")
	public ApiResponse<AdminCacheResult.Overview> overview() {
		return ApiResponse.success(adminCacheFacade.overview());
	}
}
