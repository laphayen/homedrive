package com.laphayen.homedrive.domain.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RegisterResponseDto {

    private String accessToken;
    private String refreshToken;
}
