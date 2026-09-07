package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Domain: Administrative routes and the enforcement of who may reach them.
 *
 * <p>Includes the negative half: a customer receiving 403 is the assertion that
 * matters, and it is the one usually missing.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.ADMIN)
public @interface Admin {
}
