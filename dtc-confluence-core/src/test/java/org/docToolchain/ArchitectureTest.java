package org.docToolchain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Turns the recorded design decisions into rules the build enforces.
 *
 * <p>Each rule here corresponds to an entry in {@code docs/decisions.adoc}. A decision that
 * only lives in prose erodes; one that breaks the build does not.</p>
 */
@AnalyzeClasses(packages = "org.docToolchain", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The library must stay usable from any build tool, and from none.
     */
    @ArchTest
    static final ArchRule noBuildToolDependencies = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("org.gradle..", "org.apache.maven..")
            .because("the library is consumed from Maven, Gradle and a CLI alike, "
                    + "so it must not bind to any of them");

    /**
     * The Asciidoctor backend is deferred precisely to keep JRuby out of the core.
     */
    @ArchTest
    static final ArchRule noAsciidoctorDependencies = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("org.asciidoctor..", "org.jruby..")
            .because("the core consumes HTML and storage format; an Asciidoctor converter "
                    + "would be a separate module, so that JRuby stays optional");

    /**
     * Transforming HTML fragments is independent of talking to Confluence, and stays that way.
     */
    @ArchTest
    static final ArchRule transformersDoNotReachForTheApi = noClasses()
            .that().resideInAPackage("..atlassian.transformer..")
            .should().dependOnClassesThat().resideInAPackage("..confluence.clients..")
            .because("transformers turn markup into markup and must remain testable "
                    + "without any HTTP in sight");

    @ArchTest
    static final ArchRule packagesAreFreeOfCycles = slices()
            .matching("org.docToolchain.(**)")
            .should().beFreeOfCycles();
}
