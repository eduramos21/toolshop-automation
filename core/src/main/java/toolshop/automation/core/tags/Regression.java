package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Depth: Everything worth running, but not worth blocking a merge on.
 *
 * <p>The default depth. A test with no depth tag is still selected by
 * {@code -Ptags="!quarantine"}, which is what CI runs, so forgetting this tag
 * does not silently drop a test from every job.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.REGRESSION)
public @interface Regression {
}
