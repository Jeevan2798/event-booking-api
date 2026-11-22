package com.cactro.booking.security;

import com.cactro.booking.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.Arrays;

/** Role-based access control: 401 if no/invalid token, 403 if wrong role. */
@Configuration
public class AuthInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    public static final String ATTR = "authUser";
    private final JwtService jwt;

    public AuthInterceptor(JwtService jwt) { this.jwt = jwt; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RequireRole rr = hm.getMethodAnnotation(RequireRole.class);
        if (rr == null) return true;
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw new ApiException(401, "Missing bearer token");
        AuthUser user;
        try { user = jwt.parse(h.substring(7)); }
        catch (Exception e) { throw new ApiException(401, "Invalid or expired token"); }
        if (Arrays.stream(rr.value()).noneMatch(r -> r == user.role()))
            throw new ApiException(403, "Forbidden: requires role " + Arrays.toString(rr.value()));
        req.setAttribute(ATTR, user);
        return true;
    }
}
