package modi.backend.ingestionv2.common.queue;

import java.util.Optional;

import modi.backend.ingestionv2.common.event.IngestionEventType;

/**
 * 이벤트가 나가는 대기열의 배정표.
 *
 * <ul>
 *   <li>분리 기준은 단계가 아니라 벤더 - 굶김의 원인이 벤더 한도이기 때문</li>
 *   <li>external 표시는 컨슈머 수 산정의 근거 - 외부 호출 스트림은 더 두껍게 붙임</li>
 *   <li>STAGED는 배정 없음(Optional.empty) - 소비자가 없어 스트림에 넣으면 미처리로 영원히 남음</li>
 * </ul>
 */
public enum IngestionStream {

	CULTURE("ingestion.culture", true),
	AI("ingestion.ai", true),
	GOOGLE("ingestion.google", true),
	DB("ingestion.db", false);

	private final String key;
	private final boolean external;

	IngestionStream(String key, boolean external) {
		this.key = key;
		this.external = external;
	}

	public String key() {
		return key;
	}

	/** 외부 API를 호출하는 스트림 여부 - 컨슈머 수를 다르게 잡는 기준. */
	public boolean external() {
		return external;
	}

	/** 이벤트의 배정 스트림. 소비자가 없는 이벤트는 비어 있는 값을 돌려준다. */
	public static Optional<IngestionStream> of(IngestionEventType type) {
		return switch (type) {
			case DETAIL_READY -> Optional.of(CULTURE);
			case GENRE_READY -> Optional.of(AI);
			case HOURS_READY -> Optional.of(GOOGLE);
			case COLLECTED, ENRICHED, INSPECTED -> Optional.of(DB);
			case STAGED -> Optional.empty();
		};
	}
}
