package ru.marketplace;

import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.api.AuthApi;
import ru.marketplace.model.*;
import java.util.Locale;
import java.util.UUID;

@RestController
public class AuthController implements AuthApi, ApplicationRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final Tokens tokens;
    private final String adminEmail;
    private final String adminPassword;
    private final String dummyHash;

    public AuthController(JdbcTemplate db, PasswordEncoder passwords, Tokens tokens,
                          @Value("${marketplace.admin-email}") String adminEmail,
                          @Value("${marketplace.admin-password}") String adminPassword) {
        this.db = db; this.passwords = passwords; this.tokens = tokens;
        this.adminEmail = adminEmail; this.adminPassword = adminPassword;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Override public void run(ApplicationArguments args) {
        if (!adminEmail.isBlank() && !adminPassword.isBlank()) {
            if (adminPassword.length() < 8 || adminPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                throw new IllegalArgumentException("ADMIN_PASSWORD must be 8+ characters and <=72 UTF-8 bytes");
            // Never silently promote an account that existed before bootstrap.
            db.update("INSERT INTO users(id,email,password_hash,role) VALUES (?,?,?,'ADMIN') ON CONFLICT(email) DO NOTHING",
                    UUID.randomUUID(), normalize(adminEmail), passwords.encode(adminPassword));
        }
    }

    @Override @Transactional
    public ResponseEntity<UserResponse> register(RegisterRequest request) {
        UUID id = UUID.randomUUID();
        String role = request.getRole() == null ? "USER" : request.getRole().getValue();
        int created = db.update("INSERT INTO users(id,email,password_hash,role) VALUES (?,?,?,?::user_role) ON CONFLICT(email) DO NOTHING",
                id, normalize(request.getEmail()), passwords.encode(request.getPassword()), role);
        if (created == 0) throw new BusinessException(409, "EMAIL_ALREADY_EXISTS", "Email already registered");
        return ResponseEntity.status(201).body(new UserResponse().id(id).email(normalize(request.getEmail())).role(Role.fromValue(role)));
    }

    @Override @Transactional
    public ResponseEntity<TokenResponse> login(LoginRequest request) {
        var rows = db.queryForList("SELECT * FROM users WHERE email=?", normalize(request.getEmail()));
        String hash = rows.isEmpty() ? dummyHash : (String) rows.getFirst().get("password_hash");
        if (!passwords.matches(request.getPassword(), hash) || rows.isEmpty())
            throw new BusinessException(401, "INVALID_CREDENTIALS", "Invalid email or password");
        var user = rows.getFirst();
        return ResponseEntity.ok(tokens.issue((UUID) user.get("id"), user.get("role").toString()));
    }

    @Override @Transactional
    public ResponseEntity<TokenResponse> refresh(RefreshRequest request) {
        try {
            var claims = tokens.parse(request.getRefreshToken(), "refresh");
            UUID userId = UUID.fromString(claims.getSubject());
            int used = db.update("UPDATE refresh_tokens SET revoked=true WHERE id=? AND user_id=? AND revoked=false AND expires_at>clock_timestamp()",
                    UUID.fromString(claims.getId()), userId);
            if (used != 1) throw new BusinessException(401, "REFRESH_TOKEN_INVALID", "Refresh token expired, revoked, or already used");
            String role = db.queryForObject("SELECT role::text FROM users WHERE id=?", String.class, userId);
            return ResponseEntity.ok(tokens.issue(userId, role));
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(401, "REFRESH_TOKEN_INVALID", "Invalid refresh token");
        }
    }

    private String normalize(String email) { return email.toLowerCase(Locale.ROOT); }
}
