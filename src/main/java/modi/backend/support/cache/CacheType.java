package modi.backend.support.cache;

/** 캐시가 어느 저장소를 쓰는지. 값 셋뿐이고 각자 들고 다닐 데이터가 없어 enum으로 만듬 */
public enum CacheType {

    LOCAL, // 로컬 캐시만
    REDIS, // 레디스만
    TWO_TIER // L1(로컬) -> L2(Redis). 전시 캐시의 기본형

}
