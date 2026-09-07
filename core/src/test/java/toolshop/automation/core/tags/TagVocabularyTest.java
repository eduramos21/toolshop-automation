package toolshop.automation.core.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the tag vocabulary against itself.
 *
 * <p>Composed annotations remove the typo: {@code @Smoek} does not compile. They
 * introduce two new ways to be silently wrong, and both produce an annotation
 * that applies cleanly and does nothing:
 *
 * <ul>
 *   <li>a {@code @Tag} value that does not match the annotation's name, so
 *       {@code @Smoke} tags something other than {@code smoke}
 *   <li>a missing {@code RUNTIME} retention, so the tag is invisible to the
 *       platform and the test belongs to no suite
 * </ul>
 *
 * <p>Either one produces a test excluded from every job while looking correct at
 * its declaration site - the same silent-omission failure the vocabulary exists
 * to prevent, one level down. Hence this.
 */
class TagVocabularyTest {

    /**
     * Listed by hand on purpose. A new annotation added without touching this
     * list fails {@link #theConstantsAndTheAnnotationsAreTheSameSet()}, which is
     * the reminder.
     */
    private static final List<Class<? extends Annotation>> ANNOTATIONS = List.of(
            Ui.class, Api.class, Db.class, Contract.class,
            Smoke.class, Regression.class,
            Storefront.class, Checkout.class, Admin.class, Auth.class,
            Slow.class, Quarantine.class);

    static List<Class<? extends Annotation>> annotations() {
        return ANNOTATIONS;
    }

    /**
     * The lowercase-name rule is not cosmetic: {@code verifyTestSelection} lists
     * the vocabulary in its failure message by reading these file names, rather
     * than keeping a second copy of the list in the build. That only stays true
     * while the name and the tag agree.
     */
    @ParameterizedTest
    @MethodSource("annotations")
    void carriesATagMatchingItsOwnName(Class<? extends Annotation> annotation) {
        String expected = annotation.getSimpleName().toLowerCase(Locale.ROOT);

        assertEquals(expected, tagValueOf(annotation),
                () -> "@" + annotation.getSimpleName() + " must carry @Tag(\"" + expected + "\")");
    }

    @ParameterizedTest
    @MethodSource("annotations")
    void isRetainedAtRuntimeSoThePlatformCanSeeIt(Class<? extends Annotation> annotation) {
        Retention retention = annotation.getAnnotation(Retention.class);

        assertTrue(retention != null && retention.value() == RetentionPolicy.RUNTIME,
                () -> "@" + annotation.getSimpleName() + " is not @Retention(RUNTIME), so the tag it"
                        + " carries does not exist at runtime and every test using it belongs to no suite");
    }

    @ParameterizedTest
    @MethodSource("annotations")
    void appliesToBothAClassAndAMethod(Class<? extends Annotation> annotation) {
        Target target = annotation.getAnnotation(Target.class);
        Set<ElementType> targets = target == null ? Set.of() : Set.of(target.value());

        assertTrue(targets.containsAll(Set.of(ElementType.TYPE, ElementType.METHOD)),
                () -> "@" + annotation.getSimpleName() + " must apply to a class and a method, but targets "
                        + targets);
    }

    @Test
    void theConstantsAndTheAnnotationsAreTheSameSet() {
        Set<String> fromAnnotations = ANNOTATIONS.stream()
                .map(TagVocabularyTest::tagValueOf)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(constants(), fromAnnotations,
                "every constant in Tags needs an annotation and every annotation needs a constant");
    }

    @Test
    void tagsIsConstantsOnlyAndCannotBeInstantiated() {
        assertTrue(Arrays.stream(Tags.class.getDeclaredFields())
                        .allMatch(field -> Modifier.isStatic(field.getModifiers())
                                && Modifier.isFinal(field.getModifiers())),
                "Tags holds constants only");
        assertTrue(Arrays.stream(Tags.class.getDeclaredConstructors())
                        .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())),
                "Tags is not meant to be instantiated; tests apply the annotations, not the constants");
    }

    private static String tagValueOf(Class<? extends Annotation> annotation) {
        org.junit.jupiter.api.Tag tag = annotation.getAnnotation(org.junit.jupiter.api.Tag.class);
        assertTrue(tag != null, () -> "@" + annotation.getSimpleName() + " carries no @Tag at all");
        return tag.value();
    }

    private static Set<String> constants() {
        Set<String> values = new TreeSet<>();
        for (Field field : Tags.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    values.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("Tags." + field.getName() + " is not readable", e);
                }
            }
        }
        return values;
    }
}
