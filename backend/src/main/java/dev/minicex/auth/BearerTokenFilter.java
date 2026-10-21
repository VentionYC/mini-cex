package dev.minicex.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerTokenFilter extends OncePerRequestFilter {

    private final AuthService service;

    public BearerTokenFilter(AuthService service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);

        if (!headers.hasMoreElements()) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = headers.nextElement();

        if (headers.hasMoreElements()
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(response);
            return;
        }

        var principal = service.authenticate(header.substring(7));

        if (principal.isEmpty()) {
            unauthorized(response);
            return;
        }

        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal.orElseThrow(),
                        null,
                        List.of());

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public static void unauthorized(HttpServletResponse response)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        response.getWriter().write("""
                {"code":"UNAUTHORIZED","message":"A valid bearer token is required"}
                """);
    }
}