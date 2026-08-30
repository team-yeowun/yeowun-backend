package modi.backend.ingestionv2.common;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionEventHandler;

/** 이벤트를 받아 기록만 하는 소비자. 실패를 흉내 낼 때는 동작을 갈아끼운다. */
public class RecordingEventHandler implements IngestionEventHandler {

	private final Set<IngestionEventType> claimed;
	private final List<String> received = new ArrayList<>();
	private Consumer<String> behavior = key -> {
	};

	public RecordingEventHandler(IngestionEventType... types) {
		this.claimed = EnumSet.copyOf(List.of(types));
	}

	@Override
	public boolean supports(IngestionEventType type) {
		return claimed.contains(type);
	}

	@Override
	public void handle(String vendorKey) {
		received.add(vendorKey);
		behavior.accept(vendorKey);
	}

	public List<String> received() {
		return List.copyOf(received);
	}

	public void behaveWith(Consumer<String> behavior) {
		this.behavior = behavior;
	}

	public void reset() {
		received.clear();
		behavior = key -> {
		};
	}
}
