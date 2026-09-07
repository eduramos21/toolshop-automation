package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Layer: checks the rendered page for accessibility violations.
 *
 * <p>Its own suite, so a violation does not block a functional run. That is not
 * a statement about how much accessibility matters - it is that the two failures
 * need different readers and different fixes, and mixing them means the louder
 * one buries the other.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.A11Y)
public @interface A11y {
}
