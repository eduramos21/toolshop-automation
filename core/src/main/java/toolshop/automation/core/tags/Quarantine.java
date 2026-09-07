package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Escape hatch: Known unreliable, excluded from the blocking run, and tracked.
 *
 * <p>This is the sanctioned answer to a flaky test. Never a retry: a retry that
 * passes on the second attempt reports green, so a test failing half the time
 * looks healthy forever and the underlying race is never found.
 *
 * <p>Requirements for applying it: a linked issue in a comment, and an owner.
 * CI runs {@code -Ptags="!quarantine"} on every pull request and
 * {@code -Ptags=quarantine} on a schedule, so quarantined tests stay visible
 * rather than becoming permanently ignored.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.QUARANTINE)
public @interface Quarantine {
}
