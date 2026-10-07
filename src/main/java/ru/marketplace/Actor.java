package ru.marketplace;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;

/** Authenticated identity, not an API DTO. */
public record Actor(UUID id, String role) {
    public static Actor current() {
        return (Actor) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
    public boolean admin() { return role.equals("ADMIN"); }
    public void allow(String... roles) {
        if (!java.util.List.of(roles).contains(role)) throw new BusinessException(403, "ACCESS_DENIED", "Role does not allow this operation");
    }
}
