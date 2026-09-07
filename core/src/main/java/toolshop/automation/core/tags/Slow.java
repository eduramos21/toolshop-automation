package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Escape hatch: Correct but expensive, so it can be excluded by expression.
 *
 * <p>{@code -Ptags="regression & !slow"}. An admission about cost, not about
 * reliability - a slow test is expected to pass every time.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.SLOW)
public @interface Slow {
}
