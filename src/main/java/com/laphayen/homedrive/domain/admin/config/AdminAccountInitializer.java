package com.laphayen.homedrive.domain.admin.config;

import com.laphayen.homedrive.domain.user.entity.User;
import com.laphayen.homedrive.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.username:nathan}")
    private String adminUsername;

    @Value("${admin.password:QWER!@#$}")
    private String adminPassword;

    @Value("${admin.email:nathan@homedrive.local}")
    private String adminEmail;

    @Override
    public void run(ApplicationArguments args) {
        User admin = userRepository.findByUsername(adminUsername)
                .orElseGet(() -> User.builder()
                        .username(adminUsername)
                        .email(resolveAdminEmail())
                        .build());

        admin.setRole("ADMIN");
        if (admin.getPassword() == null || !passwordEncoder.matches(adminPassword, admin.getPassword())) {
            admin.setPassword(passwordEncoder.encode(adminPassword));
        }
        if (admin.getEmail() == null || admin.getEmail().isBlank()) {
            admin.setEmail(adminEmail);
        }

        userRepository.save(admin);
    }

    private String resolveAdminEmail() {
        if (!userRepository.existsByEmail(adminEmail)) {
            return adminEmail;
        }

        int suffix = 1;
        String candidate;
        do {
            candidate = adminUsername + "+admin" + suffix + "@homedrive.local";
            suffix++;
        } while (userRepository.existsByEmail(candidate));

        return candidate;
    }
}
