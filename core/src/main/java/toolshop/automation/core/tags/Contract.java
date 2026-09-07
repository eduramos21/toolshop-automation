package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Layer: Validates responses against the OpenAPI specification.
 *
 * <p>Its own suite, because a contract break and a behaviour break need
 * different readers. The first is a conversation with whoever owns the spec.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.CONTRACT)
public @interface Contract {
}
