package com.sapari.product.domain.model.product;

import com.sapari.product.domain.exception.InvalidProductTagException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 상품 태그 규칙 정책. 등록·수정에서 공통으로 쓰는 태그 검증을 한곳에 모은다.
 *
 * <p>규칙: 최대 {@value #MAX_TAGS}개, 각 {@value #MAX_TAG_LENGTH}자 이내, 한글·영문·숫자만 허용(특수문자·공백 불가).
 */
public final class ProductTagPolicy {

    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG_LENGTH = 20;
    private static final Pattern TAG_PATTERN = Pattern.compile("^[0-9A-Za-z가-힣]+$");

    private ProductTagPolicy() {
    }

    /**
     * 태그 목록을 검증한다. null·빈 목록은 통과(태그 없음).
     *
     * @throws InvalidProductTagException 개수 초과·길이 초과·허용되지 않는 문자가 있는 경우
     */
    public static void validate(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        if (tags.size() > MAX_TAGS) {
            throw new InvalidProductTagException("태그는 최대 " + MAX_TAGS + "개입니다: " + tags.size());
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank() || tag.length() > MAX_TAG_LENGTH
                    || !TAG_PATTERN.matcher(tag).matches()) {
                throw new InvalidProductTagException("유효하지 않은 태그: " + tag);
            }
        }
    }
}
