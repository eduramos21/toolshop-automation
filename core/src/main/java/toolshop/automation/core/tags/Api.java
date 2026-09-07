package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Layer: Drives the REST API directly, with no browser.
 *
 * <p>The layer most tests should be written at. A behaviour that can be checked
 * here does not need a browser to check it, and the browser is where the
 * flakiness and the runtime are.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.API)
public @interface Api {
}
