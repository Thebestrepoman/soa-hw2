package ru.marketplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Configuration
public class Security {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }

    @Bean SecurityFilterChain chain(HttpSecurity http, Tokens tokens, ObjectMapper json) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/auth/**", "/openapi.yaml").permitAll().anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> write(res, json, 401, "TOKEN_INVALID", "Access token required"))
                        .accessDeniedHandler((req, res, ex) -> write(res, json, 403, "ACCESS_DENIED", "Access denied")))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                            throws ServletException, IOException {
                        String header = req.getHeader("Authorization");
                        if (header != null && !req.getServletPath().startsWith("/auth/")) {
                            try {
                                if (!header.startsWith("Bearer ")) throw new IllegalArgumentException();
                                var claims = tokens.parse(header.substring(7), "access");
                                Actor actor = new Actor(UUID.fromString(claims.getSubject()), claims.get("role", String.class));
                                SecurityContextHolder.getContext().setAuthentication(
                                        new UsernamePasswordAuthenticationToken(actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()))));
                                req.setAttribute("user_id", actor.id().toString());
                            } catch (ExpiredJwtException ex) {
                                write(res, json, 401, "TOKEN_EXPIRED", "Access token expired"); return;
                            } catch (JwtException | IllegalArgumentException ex) {
                                write(res, json, 401, "TOKEN_INVALID", "Invalid access token"); return;
                            }
                        }
                        chain.doFilter(req, res);
                    }
                }, UsernamePasswordAuthenticationFilter.class).build();
    }

    static void write(HttpServletResponse res, ObjectMapper json, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        json.writeValue(res.getOutputStream(), Errors.body(code, message, null));
    }
}
