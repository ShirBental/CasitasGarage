package home.casita.security.access;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import home.casita.security.store.StoreUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * In front of Spring's passkey registration endpoints. A registration that is refused (its verification failed,
 * or Casita refused the enrollment) would otherwise escape as an exception, a 500 with a stack trace in the log;
 * here it is a plain {@code 400 {"success":false}}, or 503 if the security store is unreachable. Nothing is
 * stored either way: this only changes how the refusal is reported. Spring's endpoint for deleting a passkey is
 * answered 405: revocation is for the server-side admin.
 */
public class RegistrationErrorFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RegistrationErrorFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/webauthn/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("DELETE".equals(request.getMethod())) {
            answer(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            Throwable cause = e instanceof ServletException && e.getCause() != null ? e.getCause() : e;
            if (response.isCommitted()) throw e;
            boolean unavailable = cause instanceof StoreUnavailableException;
            // The exception's kind only: its message may describe the authenticator's data.
            log.warn("Passkey registration refused ({})", cause.getClass().getSimpleName());
            response.resetBuffer();
            answer(response, unavailable ? HttpServletResponse.SC_SERVICE_UNAVAILABLE : HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private static void answer(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false}");
    }
}
