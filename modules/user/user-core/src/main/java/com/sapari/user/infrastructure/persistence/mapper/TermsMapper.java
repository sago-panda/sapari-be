package com.sapari.user.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.sapari.user.domain.model.Terms;
import com.sapari.user.infrastructure.persistence.entity.TermsEntity;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface TermsMapper {

    default TermsEntity toEntity(Terms terms) {
        return TermsEntity.of(
                terms.type(),
                terms.version(),
                terms.title(),
                terms.required(),
                terms.contentUrl(),
                terms.contentFormat(),
                terms.effectiveFrom(),
                terms.active()
        );
    }

    @Mapping(target = "termsId", source = "id")
    Terms toDomain(TermsEntity entity);
}
