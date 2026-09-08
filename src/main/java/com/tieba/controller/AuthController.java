package com.tieba.controller;

import com.tieba.model.User;
import com.tieba.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/login") public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) { try { User user=auth.login(req.account(),req.password(),req.admin()); return ResponseEntity.ok(Map.of("user",user)); } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); } }
    @PostMapping("/register") public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) { try { auth.register(req.account(),req.password(),req.username()); return ResponseEntity.ok(Map.of("message","注册成功")); } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); } }
    public record LoginRequest(@NotBlank String account, @NotBlank String password, boolean admin) {}
    public record RegisterRequest(@NotBlank String account, @NotBlank String password, @NotBlank String username) {}
}
