package com.laphayen.homedrive.domain.user.controller;

import com.laphayen.homedrive.domain.user.dto.LoginRequestDto;
import com.laphayen.homedrive.domain.user.dto.LoginResponseDto;
import com.laphayen.homedrive.domain.user.dto.RegisterRequestDto;
import com.laphayen.homedrive.domain.user.dto.RegisterResponseDto;
import com.laphayen.homedrive.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDto> register(@Valid @RequestBody RegisterRequestDto requestDto) {
        return ResponseEntity.ok(userService.register(requestDto));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@Valid @RequestBody LoginRequestDto requestDto) {
        return ResponseEntity.ok(userService.login(requestDto));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization) {
        userService.logout(authorization.substring(7));
        return ResponseEntity.noContent().build();
    }

}
