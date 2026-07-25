package modi.backend.ingestion.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공데이터(CATALOG) 장르 보강(enrich) 설정. {@code app.exhibition.enrich.*} 바인딩.
 * <p>
 * 미분류 행을 배치로(=Gemini 1콜/배치) 상한만큼 반복해 전량 백필한다. 대상이 "미분류 행"이라 멱등 —
 * 반복 실행돼도 신규 행만 처리해 AI 호출을 아낀다(같은 퀄리티, 최소 비용). 상세(가격 등)는 syncCatalog가 적재 시점에 함께 채운다.
 *
 * @param genreBatchSize        장르 배치 1회(=AI 1콜)에서 분류하는 전시 수.
 * @param genreMaxBatchesPerRun 한 번의 보강 실행에서 도는 최대 배치 수(폭주 방지 상한, 이 안에서 미분류 소진 시 조기 종료).
 */
@ConfigurationProperties(prefix = "app.exhibition.enrich")
public record CatalogEnrichProperties(Integer genreBatchSize, Integer genreMaxBatchesPerRun) {

	public CatalogEnrichProperties {
		if (genreBatchSize == null || genreBatchSize <= 0) {
			// 20건은 60s 타임아웃 안에서 검증된 배치 크기(더 키우면 모델 사고 토큰↑ → ReadTimeout → 전량 랜덤 폴백 위험).
			// AI 품질(진짜 분류) 보존을 위해 20 유지 — 273건도 ~14콜(개당 273콜 대비 충분히 저렴).
			genreBatchSize = 20;
		}
		if (genreMaxBatchesPerRun == null || genreMaxBatchesPerRun <= 0) {
			// 회당 배치 수를 작게 — 한 번에 14배치를 몰아치면 무료 RPM(분당 한도)을 넘겨 429 폭풍이 나고
			// 하루 예산까지 빨리 소진돼 다른 AI(감상문)까지 굶는다. 소량씩 주기(interval-ms)에 나눠 소비한다.
			genreMaxBatchesPerRun = 3;
		}
	}
}
