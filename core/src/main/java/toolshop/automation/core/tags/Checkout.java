package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Domain: Cart, address, payment, invoice.
 *
 * <p>The one flow that spans every layer of this framework - API, UI, database
 * and mail - and so the one that proves the layers agree with each other.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.CHECKOUT)
public @interface Checkout {
}
