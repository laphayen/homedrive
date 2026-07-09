package com.laphayen.homedrive.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminUserRoleRequestDto(
        @NotBlank(message = "역할은 필수입니다.")
        String role
) {
}
