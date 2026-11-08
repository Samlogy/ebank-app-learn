package com.ebank.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives 401 and 403 from the security filters the same RFC 9457 body as every other error.
 * They happen before any controller, so the global exception handler never sees them. The
 * delegates set the status and the WWW-Authenticate header. Tokens are never logged or echoed.
 */
@Component
class SecurityProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler deniedHandler = new BearerTokenAccessDeniedHandler();
    private final SecurityRejections rejections;
    private final JsonMapper json;

    SecurityProblemResponses(SecurityRejections rejections, JsonMapper json) {
        this.rejections = rejections;
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException, jakarta.servlet.ServletException {
        rejections.countUnauthorized();
        entryPoint.commence(request, response, ex);
        write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid access token is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, jakarta.servlet.ServletException {
        rejections.countForbidden();
        deniedHandler.handle(request, response, ex);
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(body));
    }
}
