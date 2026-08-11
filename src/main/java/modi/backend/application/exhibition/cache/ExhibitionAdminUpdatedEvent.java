package modi.backend.application.exhibition.cache;

/**
 * - 관리자가 전시를 수정했다는 사실
 *   - 커밋이 확정된 뒤에만 소비됨
 *   - 커맨드가 아니라 사실이라 "무엇을 지워라"가 아니라 "무엇이 바뀌었다"만 담음
 */
public record ExhibitionAdminUpdatedEvent(Long exhibitionId) {
}
