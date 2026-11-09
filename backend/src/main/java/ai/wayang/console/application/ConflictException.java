package ai.wayang.console.application;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/** Application-level conflict mapped consistently to HTTP 409. */
public final class ConflictException extends WebApplicationException {
    public ConflictException(String message) {
        super(message, Response.Status.CONFLICT);
    }
}
