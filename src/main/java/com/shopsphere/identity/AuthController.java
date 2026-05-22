package com.shopsphere.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        var result = auth.register(request.email(), request.password());
        return new RegisterResponse(result.userId(), result.customerId());
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var token = auth.login(request.email(), request.password());
        return LoginResponse.from(token);
    }

    @PostMapping("/refresh")
    LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        var token = auth.refresh(request.refreshToken());
        return LoginResponse.from(token);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
    }

    @ExceptionHandler(AuthService.DuplicateEmailException.class)
    ResponseEntity<Void> duplicateEmail() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(AuthService.BadCredentialsException.class)
    ResponseEntity<Void> badCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(RefreshTokenService.InvalidRefreshTokenException.class)
    ResponseEntity<Void> invalidRefresh() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    record RegisterResponse(UUID userId, UUID customerId) {
    }

    record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    record LoginResponse(String accessToken,
                         long expiresIn,
                         String refreshToken,
                         long refreshExpiresIn) {
        static LoginResponse from(AuthService.Token token) {
            return new LoginResponse(
                    token.accessToken(),
                    token.expiresIn(),
                    token.refreshToken(),
                    token.refreshExpiresIn());
        }
    }
}
