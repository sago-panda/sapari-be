package com.sapari.storage.object.client;

import com.sapari.storage.object.command.ObjectPutCommand;
import com.sapari.storage.object.result.StoredObject;

/**
 * 도메인 중립 object storage 접근 포트다.
 * 도메인 모듈은 object key 정책만 정하고, bucket 선택은 공통 storage 설정이 담당한다.
 */
public interface ObjectStorageClient {

    StoredObject put(ObjectPutCommand command);

    void delete(String key);
}
