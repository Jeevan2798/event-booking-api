package com.cactro.booking.web;

import com.cactro.booking.model.Role;
import com.cactro.booking.model.User;
import com.cactro.booking.repo.UserRepository;
import com.cactro.booking.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(8);

    public AuthController(UserRepository users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    public record RegisterReq(String name, String email, String password, String role) {}
    public record LoginReq(String email, String password) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterReq r) {
        if (r.name() == null || r.email() == null || r.password() == null)
            throw new ApiException(400, "name, email, password required");
        Role role;
        try { role = Role.valueOf(String.valueOf(r.role()).toUpperCase()); }
        catch (Exception e) { throw new ApiException(400, "role must be 'organizer' or 'customer'"); }
        String email = r.email().toLowerCase();
        if (users.findByEmail(email).isPresent()) throw new ApiException(409, "Email already registered");
        User u = new User();
        u.name = r.name();
        u.email = email;
        u.passwordHash = encoder.encode(r.password());
        u.role = role;
        try { users.save(u); }
        catch (org.springframework.dao.DataIntegrityViolationException e) { throw new ApiException(409, "Email already registered"); }
        return Map.of("user", u, "token", jwt.sign(u));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq r) {
        User u = r.email() == null ? null : users.findByEmail(r.email().toLowerCase()).orElse(null);
        if (u == null || r.password() == null || !encoder.matches(r.password(), u.passwordHash))
            throw new ApiException(401, "Invalid credentials");
        return Map.of("user", u, "token", jwt.sign(u));
    }
}
