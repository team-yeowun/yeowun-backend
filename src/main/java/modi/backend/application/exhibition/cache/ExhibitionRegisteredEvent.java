package modi.backend.application.exhibition.cache;

/**
 * - 수집이 새 전시를 카탈로그에 등록했다는 사실
 *   - 커밋이 확정된 뒤에만 소비됨
 *   - 이미 있던 전시로 응답한 경우(멱등 재진입)에는 발행하지 않음 — 목록이 바뀌지 않았으므로
 */
public record ExhibitionRegisteredEvent(Long exhibitionId) {
}
