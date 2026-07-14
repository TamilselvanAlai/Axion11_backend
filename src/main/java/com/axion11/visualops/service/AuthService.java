package com.axion11.visualops.service;

import com.axion11.visualops.controller.dto.AuthResponseDto;
import com.axion11.visualops.controller.dto.LoginDto;
import com.axion11.visualops.controller.dto.RegisterDto;
import com.axion11.visualops.models.Role;
import com.axion11.visualops.models.User;
import com.axion11.visualops.models.UserIdentity;
import com.axion11.visualops.repository.UserIdentityRepository;
import com.axion11.visualops.repository.UserRepository;
import com.axion11.visualops.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleSignInService googleSignInService;

    public AuthService(AuthenticationManager authenticationManager,
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            GoogleSignInService googleSignInService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.googleSignInService = googleSignInService;
    }

    public AuthResponseDto login(LoginDto loginDto) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginDto.getEmail(), loginDto.getPassword()));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        String token = jwtTokenProvider.generateToken(authentication);

        User user = userRepository.findByEmail(loginDto.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String teamName = user.getTeams().stream()
                .findFirst().map(t -> t.getTeamName()).orElse(null);
        return new AuthResponseDto(token, user.getEmail(), user.getName(), user.getRole().name(), teamName);
    }

    public String register(RegisterDto registerDto) {
        if (userRepository.existsByEmail(registerDto.getEmail())) {
            throw new RuntimeException("User already exists with email");
        }

        User user = User.builder()
                .email(registerDto.getEmail())
                .password(passwordEncoder.encode(registerDto.getPassword()))
                .name(registerDto.getName())
                .role(Role.GUEST)
                .build();

        userRepository.save(user);

        return "User registered successfully!";
    }

    public String getGoogleAuthUrl(String state) {
        return getGoogleAuthUrl(state, null);
    }

    public String getGoogleAuthUrl(String state, String customRedirectUri) {
        if (!googleSignInService.isConfigured()) {
            throw new IllegalStateException("Google sign-in not configured on server");
        }
        return googleSignInService.buildAuthorizationUrl(state, customRedirectUri);
    }

    @Transactional
    public AuthResponseDto signInWithGoogle(String authCode) {
        return signInWithGoogle(authCode, null);
    }

    @Transactional
    public AuthResponseDto signInWithGoogle(String authCode, String customRedirectUri) {
        GoogleSignInService.GoogleUserInfo info = googleSignInService.exchangeCodeForUserInfo(authCode, customRedirectUri);
        return signInWithGoogleUserInfo(info);
    }

    /** Used by the desktop app, which completes its own OAuth handshake and hands us a verified ID token. */
    @Transactional
    public AuthResponseDto signInWithGoogleIdToken(String idToken) {
        GoogleSignInService.GoogleUserInfo info = googleSignInService.verifyIdToken(idToken);
        return signInWithGoogleUserInfo(info);
    }

    private AuthResponseDto signInWithGoogleUserInfo(GoogleSignInService.GoogleUserInfo info) {
        if (info.email == null || info.email.isEmpty()) {
            throw new RuntimeException("Google did not return an email address");
        }
        if (!info.emailVerified) {
            throw new RuntimeException("Google email is not verified — cannot sign in");
        }

        // 1) Already linked: find by provider+sub
        UserIdentity existingIdentity = userIdentityRepository.findByProviderAndProviderUserId("GOOGLE", info.sub).orElse(null);
        User user;
        if (existingIdentity != null) {
            user = existingIdentity.getUser();
        } else {
            // 2) Auto-link by email if a user already exists
            user = userRepository.findByEmail(info.email).orElse(null);
            if (user == null) {
                // 3) Auto-register a new user with role GUEST
                user = User.builder()
                        .email(info.email)
                        .name(info.name != null ? info.name : info.email)
                        // Random unguessable placeholder so password column (NOT NULL on existing schemas) is satisfied.
                        // SSO flow doesn't go through AuthenticationManager, so this password is never used.
                        .password(passwordEncoder.encode("sso-" + UUID.randomUUID()))
                        .role(Role.GUEST)
                        .build();
                user = userRepository.save(user);
            }
            // Link this Google identity to the user
            UserIdentity identity = UserIdentity.builder()
                    .user(user)
                    .provider("GOOGLE")
                    .providerUserId(info.sub)
                    .email(info.email)
                    .displayName(info.name)
                    .pictureUrl(info.picture)
                    .build();
            userIdentityRepository.save(identity);
        }

        String token = jwtTokenProvider.generateTokenForUsername(user.getEmail());
        String teamName = user.getTeams().stream()
                .findFirst().map(t -> t.getTeamName()).orElse(null);
        return new AuthResponseDto(token, user.getEmail(), user.getName(), user.getRole().name(), teamName);
    }
}
