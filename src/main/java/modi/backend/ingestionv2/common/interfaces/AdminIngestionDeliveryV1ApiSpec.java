package modi.backend.ingestionv2.common.interfaces;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import modi.backend.support.response.ApiResponse;

/**
 * 배달 계층 관리자 API 스펙.
 *
 * <ul>
 *   <li>Swagger 어노테이션만 소유 (MVC 어노테이션은 컨트롤러)</li>
 *   <li>인증 파라미터 없음 (/api-admin 게이트는 인터셉터가 담당)</li>
 * </ul>
 */
@Tag(name = "Admin Ingestion Delivery V1", description = "수집 배달 계층 운영")
public interface AdminIngestionDeliveryV1ApiSpec {

	@Operation(
			summary = "격리 목록 조회",
			description = "관리자가 아직 처리하지 않은(PENDING) 격리 항목을 오래된 순으로 돌려준다. "
					+ "retryCount 는 격리 시점까지의 시도 횟수이며, 도메인 쪽 현재 값은 원천 키로 해당 도메인 화면을 확인한다.")
	ApiResponse<IngestionDeliveryDto.DeadLetterListResponse> findDeadLetters(
			@Parameter(description = "조회 상한. 1 미만과 200 초과는 경계값으로 눕는다") int limit);

	@Operation(
			summary = "격리 항목 재주입",
			description = "격리된 사실을 아웃박스에 새 행으로 다시 적재한다. 기존 행을 되돌리는 것이 아니므로 발송 횟수는 0에서 다시 센다. "
					+ "도메인 쪽 회생을 먼저 마치지 않으면 같은 실패가 반복되어 다시 격리된다. "
					+ "이미 처리한 항목과 해석 불가 항목은 거절한다.")
	ApiResponse<IngestionDeliveryDto.RedriveResponse> redrive(
			@Parameter(description = "격리 기록 식별자") long deadLetterId);

	@Operation(
			summary = "격리 항목 무시",
			description = "처리하지 않기로 한 격리 항목을 IGNORED 로 표시해 목록에서 뺀다. 행은 기록으로 남는다. 이미 처리한 항목은 거절한다.")
	ApiResponse<IngestionDeliveryDto.IgnoreResponse> ignore(
			@Parameter(description = "격리 기록 식별자") long deadLetterId);

	@Operation(
			summary = "발행 실패 목록",
			description = "대기열 발행이 재시도 상한을 넘겨 FAILED로 걷어낸 아웃박스 행을 오래된 순으로 돌려준다. "
					+ "Redis 장애가 길어질 때 쌓이는 목록이며, 원인이 해소되면 재시도 API로 되돌린다.")
	ApiResponse<IngestionDeliveryDto.OutboxFailureListResponse> findOutboxFailures(
			@Parameter(description = "조회 상한. 1 미만과 200 초과는 경계값으로 눕는다") int limit);

	@Operation(
			summary = "발행 실패 재시도",
			description = "FAILED 행을 PENDING으로 되돌려 다음 발송 틱이 집게 한다. 시도 횟수는 0에서 다시 센다. "
					+ "FAILED가 아닌 행은 거절한다.")
	ApiResponse<IngestionDeliveryDto.OutboxRetryResponse> retryOutbox(
			@Parameter(description = "아웃박스 행 식별자") long outboxId);

	@Operation(
			summary = "스트림 상태 조회",
			description = "네 스트림의 길이와 컨슈머 수와 미처리 건수를 돌려준다. lag은 트리밍 이후 Redis가 계산하지 못하면 "
					+ "비어 있을 수 있으므로 주 지표는 미처리 건수다.")
	ApiResponse<IngestionDeliveryDto.StreamStatusListResponse> findStreams();
}
