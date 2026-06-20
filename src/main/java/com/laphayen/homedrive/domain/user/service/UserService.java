package com.laphayen.homedrive.domain.user.service;

import com.laphayen.homedrive.domain.user.dto.LoginRequestDto;
import com.laphayen.homedrive.domain.user.dto.LoginResponseDto;
import com.laphayen.homedrive.domain.user.dto.RegisterRequestDto;
import com.laphayen.homedrive.domain.user.dto.RegisterResponseDto;
import com.laphayen.homedrive.domain.user.entity.User;
import com.laphayen.homedrive.domain.user.repository.UserRepository;
import com.laphayen.homedrive.global.exception.*;
import com.laphayen.homedrive.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

    // 회원가입
    public RegisterResponseDto register(RegisterRequestDto request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new UserAlreadyExistsException("이미 존재하는 사용자명입니다.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException("이미 사용 중인 이메일입니다.");
        }

        User user = userRepository.save(User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .role("USER")
                .build());

        String accessToken = jwtTokenProvider.createAccessToken(user);
        String refreshToken = jwtTokenProvider.createRefreshToken(user);

        redisTemplate.opsForValue().set("refresh:" + user.getId(), refreshToken);

        return RegisterResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(RegisterResponseDto.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .build())
                .build();
    }

    // 로그인
    public LoginResponseDto login(LoginRequestDto request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidPasswordException("비밀번호가 일치하지 않습니다.");
        }

        String accessToken = jwtTokenProvider.createAccessToken(user);
        String refreshToken = jwtTokenProvider.createRefreshToken(user);

        redisTemplate.opsForValue().set("refresh:" + user.getId(), refreshToken);

        return LoginResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(LoginResponseDto.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .build())
                .build();
    }


}
