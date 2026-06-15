package com.sapari.user.domain.repository;

import java.util.UUID;

import com.sapari.user.domain.model.WithdrawnUserRetention;

public interface WithdrawnUserRetentionRepository {

    WithdrawnUserRetention save(WithdrawnUserRetention withdrawnUserRetention);

    boolean existsByOriginalUserId(UUID originalUserId);
}
