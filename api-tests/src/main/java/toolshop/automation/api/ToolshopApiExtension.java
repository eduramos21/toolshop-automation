package toolshop.automation.api;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import toolshop.automation.core.config.ToolshopConfig;

/**
 * Supplies {@link ToolshopApi} and {@link ToolshopConfig} to test constructors.
 *
 * <p>This is the promise {@code docs/CONFIGURATION.md} made: tests receive the
 * configuration by constructor injection rather than reaching for
 * {@code ToolshopConfig.get()} themselves. The one static read the framework
 * needs is contained here, in one place, where a run has already been validated
 * before this is ever called.
 *
 * <p>The client is cached in the launcher-session store, so there is one per test
 * JVM rather than one per class. That matters: an authentication token lasts 300
 * seconds, so a client per class would log in once per class and throw most of
 * that away. {@code StoreScope.LAUNCHER_SESSION} states that lifetime rather
 * than implying it through {@code getRoot()}, and it is not static mutable state
 * - the store is owned by the session and disposed with it.
 *
 * <p>Registered by {@code ServiceLoader} with extension auto-detection enabled,
 * so no test carries an {@code @ExtendWith}.
 */
public final class ToolshopApiExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ToolshopApiExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == ToolshopApi.class || type == ToolshopConfig.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        ToolshopApi api = apiFor(extensionContext);
        return parameterContext.getParameter().getType() == ToolshopConfig.class ? api.config() : api;
    }

    private static ToolshopApi apiFor(ExtensionContext context) {
        return context.getStore(ExtensionContext.StoreScope.LAUNCHER_SESSION, NAMESPACE)
                .computeIfAbsent(
                        ToolshopApi.class,
                        key -> new ToolshopApi(ToolshopConfig.get()),
                        ToolshopApi.class);
    }
}
