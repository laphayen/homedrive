package com.laphayen.homedrive.domain.user.service;

import com.laphayen.homedrive.domain.user.dto.LoginRequestDto;
import com.laphayen.homedrive.domain.user.dto.LoginResponseDto;
import com.laphayen.homedrive.domain.user.dto.RegisterRequestDto;
import com.laphayen.homedrive.domain.user.dto.RegisterResponseDto;
import com.laphayen.homedrive.domain.user.entity.User;
import com.laphayen.homedrive.domain.user.repository.UserRepository;
import com.laphayen.homedrive.global.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, jwtTokenProvider, redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void registerEncryptsPasswordAndIssuesTokens() {
        RegisterRequestDto request = new RegisterRequestDto();
        ReflectionTestUtils.setField(request, "username", "tester");
        ReflectionTestUtils.setField(request, "password", "password123");
        ReflectionTestUtils.setField(request, "email", "tester@example.com");

        when(passwordEncoder.encode("password123")).thenReturn("encrypted");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });
        when(jwtTokenProvider.createAccessToken(any(User.class))).thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken(any(User.class))).thenReturn("refresh-token");
        when(jwtTokenProvider.getRefreshTokenValidityMs()).thenReturn(604_800_000L);

        RegisterResponseDto response = userService.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encrypted");
        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getUser().getUsername()).isEqualTo("tester");
        verify(valueOperations).set("refresh:1", "refresh-token", Duration.ofDays(7));
    }

    @Test
    void loginVerifiesPasswordAndReplacesRefreshToken() {
        LoginRequestDto request = new LoginRequestDto();
        ReflectionTestUtils.setField(request, "username", "tester");
        ReflectionTestUtils.setField(request, "password", "password123");
        User user = User.builder()
                .id(1L)
                .username("tester")
                .password("encrypted")
                .email("tester@example.com")
                .role("USER")
                .build();

        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encrypted")).thenReturn(true);
        when(jwtTokenProvider.createAccessToken(user)).thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken(user)).thenReturn("refresh-token");
        when(jwtTokenProvider.getRefreshTokenValidityMs()).thenReturn(604_800_000L);

        LoginResponseDto response = userService.login(request);

        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getUser().getId()).isEqualTo(1L);
        verify(valueOperations).set("refresh:1", "refresh-token", Duration.ofDays(7));
    }

    @Test
    void logoutDeletesRefreshTokenAndBlacklistsAccessToken() {
        when(jwtTokenProvider.getUserId("access-token")).thenReturn(1L);
        when(jwtTokenProvider.getRemainingValidityMs("access-token")).thenReturn(60_000L);

        userService.logout("access-token");

        verify(redisTemplate).delete("refresh:1");
        verify(valueOperations).set("blacklist:access-token", "logout", Duration.ofMinutes(1));
    }
}
