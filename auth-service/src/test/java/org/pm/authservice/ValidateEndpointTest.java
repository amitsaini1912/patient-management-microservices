package org.pm.authservice;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.pm.authservice.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /validate: 200 + who the token belongs to (the gateway uses it), 401 for anything else. */
@SpringBootTest
@AutoConfigureMockMvc
class ValidateEndpointTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtUtil jwtUtil;
    @Value("${jwt.secret}") private String secret;

    @Test
    void validTokenReturnsSubjectAndRole() throws Exception {
        String token = jwtUtil.generateToken("patient@test.com", "USER");

        mvc.perform(get("/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("patient@test.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void tamperedExpiredOrMissingTokenIs401() throws Exception {
        String token = jwtUtil.generateToken("patient@test.com", "USER");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        String expired = Jwts.builder().subject("patient@test.com").claim("role", "USER")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret))).compact();

        mvc.perform(get("/validate").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());
        mvc.perform(get("/validate").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        mvc.perform(get("/validate").header("Authorization", "Basic abc")).andExpect(status().isUnauthorized());
        mvc.perform(get("/validate")).andExpect(status().isUnauthorized()); // was 400 (required header) before Day 12
    }
}
