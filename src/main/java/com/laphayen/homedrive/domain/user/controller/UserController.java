package com.laphayen.homedrive.domain.user.controller;

import com.laphayen.homedrive.domain.user.dto.RegisterRequestDto;
import com.laphayen.homedrive.domain.user.dto.RegisterResponseDto;
import com.laphayen.homedrive.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDto> register(@RequestBody RegisterRequestDto requestDto) {
        return ResponseEntity.ok(userService.register(requestDto));
    }

}
