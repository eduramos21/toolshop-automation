package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Layer: Reads the database to confirm what a UI or API action actually wrote.
 *
 * <p>Verification only. A test never seeds through this layer - test data is
 * created through the API, so that the application's own validation and side
 * effects apply and the row is shaped the way the product shapes it.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.DB)
public @interface Db {
}
