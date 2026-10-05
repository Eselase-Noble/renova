package io.renova.core.ai;

/**
 * A provider failure. {@code fatal} failures (bad credentials, unknown model) stop the AI stage;
 * the others only affect the current file.
 */
public class AiProviderException extends RuntimeException {

    private final boolean fatal;

    public AiProviderException(String message, boolean fatal, Throwable cause) {
        super(message, cause);
        this.fatal = fatal;
    }

    public AiProviderException(String message, boolean fatal) {
        this(message, fatal, null);
    }

    public boolean fatal() {
        return fatal;
    }
}
