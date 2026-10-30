package org.pm.authservice.service;

import org.pm.authservice.dto.LoginRequestDTO;
import org.pm.authservice.dto.TokenIdentityDTO;
import org.pm.authservice.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public Optional<String> authenticate(LoginRequestDTO loginRequestDTO) {
        Optional<String> token = userService.findByEmail(loginRequestDTO.getEmail())
                .filter(u -> passwordEncoder.matches(loginRequestDTO.getPassword(),
                        u.getPassword()))
                .map(u -> jwtUtil.generateToken(u.getEmail(), u.getRole()));

        return token;
    }

    /** Empty for an invalid/expired token; otherwise who it belongs to. */
    public Optional<TokenIdentityDTO> validateToken(String token) {
        try {
            Claims claims = jwtUtil.validateToken(token);
            return Optional.of(new TokenIdentityDTO(claims.getSubject(), claims.get("role", String.class)));
        } catch (JwtException e){
            return Optional.empty();
        }
    }
}