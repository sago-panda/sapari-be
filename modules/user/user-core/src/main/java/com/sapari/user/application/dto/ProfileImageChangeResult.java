package com.sapari.user.application.dto;

import com.sapari.user.domain.model.User;

public record ProfileImageChangeResult(
        User savedUser,
        String oldProfileImageKey
) {
}
