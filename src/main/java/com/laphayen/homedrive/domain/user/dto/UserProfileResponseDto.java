package com.laphayen.homedrive.domain.user.dto;

public record UserProfileResponseDto(
        Long id,
        String username,
        String email,
        String role
) {
}
