package modi.backend.ingestionv2.enrich.infra.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import modi.backend.ingestionv2.enrich.domain.genre.GenreClassifier;
import modi.backend.ingestionv2.enrich.domain.genre.GenreResult;

/**
 * 장르 분류 어댑터. 1차 공급자 실패 시 2차로 넘어간다.
 *
 * <ul>
 *   <li>폴백 순서를 아는 유일한 클래스. 공급자를 바꿔도 도메인은 그대로</li>
 *   <li>공급자당 단일 시도. 재시도는 배달 계층이 제공하므로 어댑터가 겹쳐 걸지 않음</li>
 *   <li>전 공급자 소진도 결과값. 실패한 시도 목록을 도메인이 받아 기록해야 하기 때문</li>
 *   <li>전시 1건당 개별 분류. 여러 건을 묶어 부르는 경로를 두지 않음</li>
 * </ul>
 */
@Component
public class FallbackGenreClassifier implements GenreClassifier {

	private final List<GenreVendorClient> chain;

	public FallbackGenreClassifier(List<GenreVendorClient> chain) {
		this.chain = List.copyOf(chain);
	}

	@Override
	public GenreResult classify(String title, String description) {
		List<GenreResult.Attempt> failed = new ArrayList<>();
		for (GenreVendorClient client : chain) {
			if (!client.isConfigured()) {
				continue;
			}
			try {
				String keyword = client.classify(title, description);
				return GenreResult.classified(keyword, client.provider(), client.model(), failed);
			} catch (RuntimeException failure) {
				failed.add(new GenreResult.Attempt(client.provider(), failure.getMessage()));
			}
		}
		return GenreResult.exhausted(failed);
	}
}
