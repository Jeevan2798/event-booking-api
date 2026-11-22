package com.cactro.booking.security;

import com.cactro.booking.model.Role;

public record AuthUser(Long id, Role role, String email, String name) {}
