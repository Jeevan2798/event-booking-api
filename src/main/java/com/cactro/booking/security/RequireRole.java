package com.cactro.booking.security;

import com.cactro.booking.model.Role;
import java.lang.annotation.*;

/** Put on a controller method: caller must have a valid JWT with one of these roles. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    Role[] value();
}
