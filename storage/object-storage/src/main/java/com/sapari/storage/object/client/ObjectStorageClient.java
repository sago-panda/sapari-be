package com.sapari.storage.object.client;

import com.sapari.storage.object.command.ObjectPutCommand;
import com.sapari.storage.object.result.StoredObject;

/**
 * 도메인 중립 object storage 접근 포트다.
 * 도메인 모듈은 object key 정책만 정하고, bucket 선택은 공통 storage 설정이 담당한다.
 */
public interface ObjectStorageClient {

    /** 객체를 저장하고 실제 저장에 사용된 key와 content metadata를 반환한다. */
    StoredObject put(ObjectPutCommand command);

    /** 지정한 object key를 삭제하며 provider SDK 실패는 공통 저장소 예외로 전달한다. */
    void delete(String key);
}
