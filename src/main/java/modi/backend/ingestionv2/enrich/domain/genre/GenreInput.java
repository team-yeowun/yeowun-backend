package modi.backend.ingestionv2.enrich.domain.genre;

/** 장르 분류 입력. 상세 원장에서 뽑은 제목과 설명. */
public record GenreInput(String title, String description) {
}
