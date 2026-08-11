package modi.backend.interfaces.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import modi.backend.application.admin.AdminCacheResult;
import modi.backend.support.response.ApiResponse;

@Tag(name = "Admin Cache", description = "관리자 캐시 현황(내부 콘솔)")
public interface AdminCacheV1ApiSpec {

	@Operation(summary = "캐시 히트율·적재 현황", description = """
			캐시 선언별 계층 히트율(L1/L2/미스)과 적재 상태, 그리고 무효화 경로의 건강 상태를 돌려준다.
			히트율이 -1이면 아직 조회가 없어 판단할 수 없다는 뜻이다(0%와 구분).
			""")
	ApiResponse<AdminCacheResult.Overview> overview();
}
