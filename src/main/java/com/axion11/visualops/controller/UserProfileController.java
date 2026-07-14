package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.UpdateProfileRequest;
import com.axion11.visualops.controller.dto.UserProfileDto;
import com.axion11.visualops.models.User;
import com.axion11.visualops.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> getProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(toDto(user));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserProfileDto> updateProfile(
            @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (request.name() != null && !request.name().isBlank()) {
            user.setName(request.name().trim());
        }
        if (request.email() != null && !request.email().isBlank()) {
            String newEmail = request.email().trim();
            if (!newEmail.equals(user.getEmail()) && userRepository.existsByEmail(newEmail)) {
                return ResponseEntity.badRequest().build();
            }
            user.setEmail(newEmail);
        }
        if (request.contactNumber() != null) {
            user.setContactNumber(request.contactNumber().trim());
        }
        if (request.country() != null) {
            user.setCountry(request.country().trim());
        }

        userRepository.save(user);
        return ResponseEntity.ok(toDto(user));
    }

    private UserProfileDto toDto(User user) {
        return new UserProfileDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getContactNumber(),
                user.getCountry(),
                user.getRole() != null ? user.getRole().name() : null,
                user.getTeams().stream().findFirst().map(t -> t.getTeamName()).orElse(null)
        );
    }
}
