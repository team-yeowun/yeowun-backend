package modi.backend.ingestionv2.collect.domain;

import java.time.LocalDate;

/** 한 번의 수집 실행권. token은 이전 실행이 재선점된 실행의 상태를 덮어쓰지 못하게 한다. */
public record CollectBatchClaim(LocalDate batchDate, String token) {
}
