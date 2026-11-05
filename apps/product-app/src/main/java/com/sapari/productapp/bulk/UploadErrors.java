package com.sapari.productapp.bulk;

import java.util.ArrayList;
import java.util.List;

import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse.UploadError;

/**
 * 업로드 오류 수집기. 응답에 담을 상한({@link BulkProductUploadResponse#MAX_ERRORS})까지만 보관하고 나머지는 건수만 센다.
 *
 * <p>행마다 오류가 나는 파일에서 오류 목록이 파싱 중 무한정 커지지 않도록, 상한을 응답 조립이 아니라 <b>수집 시점</b>에 건다.
 */
final class UploadErrors {

    private final List<UploadError> kept = new ArrayList<>();
    private int total;

    /**
     * 오류를 기록한다. 상한을 넘으면 보관하지 않고 건수만 늘린다.
     */
    void add(UploadError error) {
        total++;
        if (kept.size() < BulkProductUploadResponse.MAX_ERRORS) {
            kept.add(error);
        }
    }

    /**
     * 오류가 하나도 없는지.
     */
    boolean isEmpty() {
        return total == 0;
    }

    /**
     * 전체 오류 수(보관하지 않은 것 포함).
     */
    int total() {
        return total;
    }

    /**
     * 보관된 오류들(최대 상한개, 기록 순).
     */
    List<UploadError> kept() {
        return List.copyOf(kept);
    }
}
