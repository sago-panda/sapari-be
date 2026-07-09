package com.sapari.user.application.dto;

import com.sapari.user.domain.model.User;

public record ProfileImageRemoveResult(
        User savedUser,
        String oldProfileImageKey
) {
}
