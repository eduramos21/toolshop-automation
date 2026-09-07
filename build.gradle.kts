import java.io.File

// Nothing is built here. The root project owns exactly one thing: the check that
// a tag selection actually ran something.
//
// Why it exists: Gradle's `failOnNoDiscoveredTests` does not fire when a tag
// filter matches nothing. Tag filtering is post-discovery - the class is
// discovered, then removed - so "no tests discovered" is false. Measured, see
// docs/adr/0003-empty-test-selection-must-fail.md.
//
// Why it lives at the root rather than in the conventions plugin: the guard is
// per-INVOCATION, not per module. A tag naming a layer - `-Ptags=ui` - is
// *supposed* to select zero tests in api-tests and data. Only zero across the
// whole run is a mistake, and only the root project can see that.

val tagExpression = providers.gradleProperty("tags")

// Resolved at configuration time to plain Files, so the task action captures
// values and not the project. Required for the configuration cache.
val resultsDirs = subprojects.map { it.layout.buildDirectory.dir("test-results/test").get().asFile }

// The annotation file names in core ARE the tag vocabulary, so listing them
// needs no second copy of the list to drift. Degrades to an empty list if the
// directory ever moves; the message is then shorter, not wrong.
val vocabulary = (file("core/src/main/java/toolshop/automation/core/tags").listFiles() ?: emptyArray())
    .map { it.name }
    .filter { it.endsWith(".java") && it != "Tags.java" }
    .map { it.removeSuffix(".java").lowercase() }
    .sorted()

tasks.register("verifyTestSelection") {
    group = "verification"
    description = "Fails the build if -Ptags selected no tests anywhere."

    val expression = tagExpression.getOrElse("")
    val dirs = resultsDirs
    val tags = vocabulary

    // A verification task with no inputs of its own. It must run whenever its
    // finalized test tasks do, including when those were up to date.
    outputs.upToDateWhen { false }

    doLast {
        fun executedIn(dir: File): Int {
            val xml = dir.listFiles()?.filter { it.name.endsWith(".xml") } ?: emptyList()
            return xml.sumOf { report ->
                val header = report.readText().substringAfter("<testsuite", "").substringBefore(">")
                fun attribute(name: String) =
                    Regex("""$name="(\d+)"""").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                attribute("tests") - attribute("skipped")
            }
        }

        val executed = dirs.sumOf(::executedIn)
        if (executed > 0) {
            return@doLast
        }

        throw GradleException(
            """
            -Ptags="$expression" selected no tests. Nothing ran.

            A selection that runs nothing must never report green. Unlike a red build it
            produces no signal at all, so it is the one failure mode worth failing loudly.

            The tag vocabulary is${if (tags.isEmpty()) " in docs/TAGS.md" else ":\n              ${tags.joinToString(", ")}"}

            Tags compose with a JUnit tag expression, and compound expressions must be
            quoted because & and | are shell operators:

              ./run test -Ptags=smoke
              ./run test -Ptags="ui & checkout"
              ./run test -Ptags="regression & !slow"

            See docs/TAGS.md.
            """.trimIndent()
        )
    }
}
