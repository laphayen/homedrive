package com.laphayen.homedrive.domain.user.dto;

import lombok.Getter;

@Getter
public class RegisterRequestDto {

    private String username;
    private String password;
    private String email;
    private String code; // 인증 코드 또는 초대 코드 등

}
