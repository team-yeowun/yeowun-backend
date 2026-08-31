package modi.backend.ingestionv2.collect.interfaces;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import modi.backend.support.response.ApiResponse;

/**
 * 수집 관리자 API 스펙.
 *
 * <ul>
 *   <li>Swagger 어노테이션만 소유 (MVC 어노테이션은 컨트롤러)</li>
 *   <li>인증 파라미터 없음 (/api-admin 게이트는 인터셉터가 담당)</li>
 * </ul>
 */
@Tag(name = "Admin Collect V1", description = "수집 회차 수동 실행")
public interface AdminCollectV1ApiSpec {

	@Operation(
			summary = "수집 회차 수동 실행",
			description = "지정한 회차를 실행한다. 이미 선점된 회차면 claimed=false 로 아무 일도 하지 않고 돌아온다. "
					+ "목록 조회가 실패해 비어 있는 채로 닫힌 회차를 메우는 용도이며, 목록 조회는 원천의 최신 등록분을 "
					+ "받아오므로 batchDate 는 조회 조건이 아니라 회차 식별자다.")
	ApiResponse<CollectDto.BatchRunResponse> run(CollectDto.BatchRunRequest request);
}
