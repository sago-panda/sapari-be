package com.sapari.product.application.service;

import com.sapari.product.command.ProductOptionTypeCommand;
import com.sapari.product.command.ProductOptionValueCommand;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import java.util.List;

/**
 * 옵션 타입/값 커맨드(-api)를 도메인 모델로 변환하는 공통 매퍼. 등록·옵션수정 유스케이스가 공유한다(id는 영속 시 부여되므로 null).
 */
final class ProductOptionCommandMapper {

    private ProductOptionCommandMapper() {
    }

    /**
     * 옵션 타입 커맨드 목록을 도메인 모델 목록으로 변환한다. null이면 빈 목록.
     */
    static List<ProductOptionTypeModel> toOptionTypeModels(List<ProductOptionTypeCommand> commands) {
        if (commands == null) {
            return List.of();
        }
        return commands.stream()
                .map(type -> ProductOptionTypeModel.create(
                        type.name(),
                        type.attributeGroupId(),
                        type.sortOrder(),
                        toOptionValueModels(type.values())))
                .toList();
    }

    /**
     * 옵션 값 커맨드 목록을 도메인 모델 목록으로 변환한다. null이면 빈 목록.
     */
    private static List<ProductOptionValueModel> toOptionValueModels(List<ProductOptionValueCommand> commands) {
        if (commands == null) {
            return List.of();
        }
        return commands.stream()
                .map(value -> ProductOptionValueModel.create(
                        value.value(),
                        value.attributePresetId(),
                        value.metadata(),
                        value.priceDelta(),
                        value.sortOrder()))
                .toList();
    }
}
