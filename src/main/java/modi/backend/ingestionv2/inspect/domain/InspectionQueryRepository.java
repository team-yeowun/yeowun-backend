package modi.backend.ingestionv2.inspect.domain;

import java.util.List;

/**
 * 관리자 조회 포트.
 *
 * <ul>
 *   <li>애그리거트 포트와 분리 (소비 경로는 단건 멱등 조회만 필요)</li>
 *   <li>페이지 타입을 쓰지 않고 오프셋과 개수로 받음 (도메인이 스프링 데이터를 모르게 함)</li>
 *   <li>사유는 선택 조건 (null이면 전체 반려)</li>
 * </ul>
 */
public interface InspectionQueryRepository {

	List<Inspection> findRejected(RejectReason reason, int offset, int limit);

	long countRejected(RejectReason reason);
}
