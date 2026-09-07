package toolshop.automation.core.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Layer: Drives the Angular storefront through a browser.
 *
 * <p>Also selects the module: every {@code @Ui} test lives in {@code ui-tests},
 * so {@code -Ptags=ui} runs that module and nothing in the others.
 *
 * @see Tags
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag(Tags.UI)
public @interface Ui {
}
