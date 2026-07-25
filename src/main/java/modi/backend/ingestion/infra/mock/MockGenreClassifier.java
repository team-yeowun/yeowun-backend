package modi.backend.ingestion.infra.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.domain.exhibition.genre.GenreClassifier;

/**
 * 로컬/CI/키없음 환경의 <b>결정적</b> 장르 분류기 — 제목 해시로 마스터에서 1개를 고른다(AI 호출·비용 0).
 * {@code app.exhibition.genre.classifier=mock}(기본)일 때만 빈으로 등록되어 주 분류기(@Primary)가 된다(운영 prod만 gemini 체인).
 *
 * <p>Random(무작위) 분류기를 대체한다(ADR-11): 같은 입력 → 같은 출력이라 테스트 단언이 가능하고,
 * 산출물엔 {@code provider=MOCK}이 붙어 실 분류와 구분된다. 계약(실패 시 예외)상 이 구현은 실패하지 않는다 —
 * 마스터가 비어 있지 않는 한 항상 유효 장르를 낸다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.exhibition.genre.classifier", havingValue = "mock", matchIfMissing = true)
public class MockGenreClassifier implements GenreClassifier {

	@Override
	public GenreResult classify(GenreClassificationRequest request) {
		// 허용 집합도 요청이 들고 온다 — 전역 마스터를 따로 읽지 않는다(요청이 좁혀 오면 그 안에서 고른다).
		String seed = request == null || request.subject() == null ? "" : request.subject();
		int index = Math.floorMod(seed.hashCode(), request.allowedGenres().size());
		return GenreResult.mock(request.allowedGenres().get(index));
	}
}
