package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.AuthResponseDto;
import com.axion11.visualops.controller.dto.LoginDto;
import com.axion11.visualops.controller.dto.RegisterDto;
import com.axion11.visualops.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginDto loginDto) {
        try {
            AuthResponseDto response = authService.login(loginDto);
            return ResponseEntity.ok(response);
        } catch (AuthService.AccountNotActivatedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /** Public: redeems an invitation token and sets the invitee's password. */
    @PostMapping("/activate")
    public ResponseEntity<?> activate(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String password = body.get("password");
        if (token == null || token.isEmpty() || password == null || password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters"));
        }
        try {
            AuthResponseDto response = authService.activateAccount(token, password);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody RegisterDto registerDto) {
        try {
            String response = authService.register(registerDto);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    /** Returns the URL the frontend should open in a popup for Google sign-in. */
    @PostMapping("/google/auth-url")
    public ResponseEntity<Map<String, Object>> googleAuthUrl(@RequestBody(required = false) Map<String, String> requestBody) {
        try {
            String origin = requestBody != null ? requestBody.get("origin") : null;
            String customRedirectUri = null;
            if (origin != null && !origin.isEmpty()) {
                customRedirectUri = origin + "/oauth/callback/google-signin";
            }
            String state = UUID.randomUUID().toString().substring(0, 16);
            String url = authService.getGoogleAuthUrl(state, customRedirectUri);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authUrl", url);
            body.put("state", state);
            body.put("configured", true);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("configured", false);
            body.put("error", e.getMessage());
            return ResponseEntity.ok(body);
        }
    }

    /** Receives {code, state, redirectUri} from the popup, exchanges with Google, returns Axion11 JWT. */
    @PostMapping("/google/callback")
    public ResponseEntity<?> googleCallback(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        String redirectUri = body.get("redirectUri");
        if (code == null || code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing code"));
        }
        try {
            AuthResponseDto response = authService.signInWithGoogle(code, redirectUri);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage() == null ? "auth failed" : e.getMessage()));
        }
    }

    /**
     * Used by the desktop app: it completes its own OAuth handshake locally (its packaged
     * webview has no fixed web origin a "Web application" OAuth client's redirect_uri could
     * target), so it hands us an already-verified Google ID token instead of a code.
     */
    @PostMapping("/google/token-signin")
    public ResponseEntity<?> googleTokenSignin(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        if (idToken == null || idToken.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing idToken"));
        }
        try {
            AuthResponseDto response = authService.signInWithGoogleIdToken(idToken);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage() == null ? "auth failed" : e.getMessage()));
        }
    }
}
