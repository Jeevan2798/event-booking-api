package com.cactro.booking.security;

import com.cactro.booking.model.Role;
import com.cactro.booking.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;

    public JwtService(@Value("${app.jwt-secret}") String secret) {
        if (secret == null || secret.length() < 32)
            throw new IllegalStateException("JWT_SECRET env var must be set (at least 32 characters)");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String sign(User u) {
        return Jwts.builder().subject(String.valueOf(u.id))
                .claim("role", u.role.name()).claim("email", u.email).claim("name", u.name)
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 7L * 86400_000))
                .signWith(key).compact();
    }

    public AuthUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthUser(Long.valueOf(c.getSubject()), Role.valueOf(c.get("role", String.class)),
                c.get("email", String.class), c.get("name", String.class));
    }
}
