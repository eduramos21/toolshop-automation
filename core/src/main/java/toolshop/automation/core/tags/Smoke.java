package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Depth: Fast, and worth blocking a merge on.
 *
 * <p>The bar is deliberately high: if a smoke test fails, the build is not
 * worth deploying. Everything else is {@code @Regression}. A smoke suite that
 * grows to cover everything stops being a signal and becomes the whole run.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.SMOKE)
public @interface Smoke {
}
