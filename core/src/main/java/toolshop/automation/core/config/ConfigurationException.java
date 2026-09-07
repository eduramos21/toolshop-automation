package toolshop.automation.core.config;

/**
 * Thrown when configuration cannot be resolved, carrying every problem found
 * rather than only the first.
 *
 * <p>It is its own type deliberately. {@link ConfigValidationListener} exists to
 * turn this and nothing else into a dead run, and the loader's tests assert on
 * the type rather than on message text.
 */
public final class ConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ConfigurationException(String message) {
        super(message);
    }
}
