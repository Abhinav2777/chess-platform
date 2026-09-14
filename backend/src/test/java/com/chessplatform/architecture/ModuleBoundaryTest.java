package com.chessplatform.architecture;

import com.chessplatform.ChessPlatformApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the module boundaries described in {@code docs/REPOSITORY_STRUCTURE.md}.
 *
 * <p>This test exists because "modular monolith" is otherwise a claim rather than a
 * property. Boundaries maintained only by discipline decay — usually within weeks, and
 * always silently. By the time anyone notices, extraction has become a rewrite and the
 * architecture document has quietly become fiction.
 *
 * <p>Written in Phase 0, before there is any code to violate it. That order is
 * deliberate: retrofitting these rules onto an existing codebase means starting with a
 * list of violations to grandfather in, and a rule with exceptions is not a rule.
 */
@DisplayName("Module boundaries")
class ModuleBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackagesOf(ChessPlatformApplication.class);

    /**
     * The central rule. A module's {@code internal} package is private to that module;
     * everything else must enter through the module's facade.
     *
     * <p>{@code allowEmptyShould(true)} is required because these rules match nothing
     * until the first module lands in Phase 1. Without it ArchUnit fails a rule whose
     * subject set is empty, which would make Phase 0 unable to go green.
     */
    @Test
    @DisplayName("internal packages are unreachable from other modules")
    void internalPackagesAreModulePrivate() {
        for (String module : Modules.ALL) {
            noClasses()
                    .that().resideOutsideOfPackage("com.chessplatform." + module + "..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.chessplatform." + module + ".internal..")
                    .because(module + ".internal is private to the " + module
                             + " module; use its facade (ADR-001)")
                    .allowEmptyShould(true)
                    .check(CLASSES);
        }
    }

    /**
     * Cycles are what actually make a monolith unextractable. Two modules that reference
     * each other cannot be separated without separating both, so a single cycle can pin
     * the entire architecture in place. Catching them at commit time is the difference
     * between a five-minute fix and a refactor.
     */
    @Test
    @DisplayName("no cyclic dependencies between modules")
    void noModuleCycles() {
        SlicesRuleDefinition.slices()
                .matching("com.chessplatform.(*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    /**
     * {@code common} holds error types, ID generation, the clock abstraction and
     * pagination. The moment it depends on a business module it stops being shared
     * infrastructure and becomes a cycle waiting to happen.
     */
    @Test
    @DisplayName("common depends on no business module")
    void commonIsIndependent() {
        noClasses()
                .that().resideInAPackage("com.chessplatform.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(Modules.businessPackages())
                .because("common is shared infrastructure and must stay dependency-free")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    /**
     * Persistence types must not escape into <em>other</em> modules. A JPA entity that
     * crosses a module boundary couples the receiving module to another's storage
     * schema, so a column rename becomes a compile error somewhere that has no business
     * knowing the column exists. Worse, the receiving module gets a mutable object
     * attached to a persistence context it does not control — free to modify it, or to
     * trip a LazyInitializationException outside the transaction.
     *
     * <p><strong>Rule history.</strong> The first version of this rule forbade any class
     * outside a {@code ..domain..} or {@code ..internal..} package from touching an
     * entity at all. That was too strict and it was wrong: a module's own facade sits at
     * the module root and its entire job is to map that module's entity to a DTO. The
     * rule rejected the exact pattern it was written to encourage.
     *
     * <p>The fix is to scope by module rather than by package suffix. Within a module,
     * touching your own entity is fine; across modules it is forbidden. Worth keeping as
     * a note, because an architecture rule that fires on correct code gets an
     * {@code @ArchIgnore} within a week, and then it guards nothing.
     */
    @Test
    @DisplayName("JPA entities do not cross module boundaries")
    void entitiesDoNotLeakAcrossModules() {
        for (String module : Modules.ALL) {
            String modulePackage = "com.chessplatform." + module + "..";
            noClasses()
                    .that().resideOutsideOfPackage(modulePackage)
                    .should().dependOnClassesThat(
                            resideInAPackage(modulePackage)
                                    .and(annotatedWith("jakarta.persistence.Entity")))
                    .because("entities belong to " + module
                             + "; cross-module traffic uses DTOs (see UserSummary)")
                    .allowEmptyShould(true)
                    .check(CLASSES);
        }
    }

    private static final class Modules {
        static final String[] ALL = {
                "identity", "chess", "game", "realtime", "matchmaking", "rating"
        };

        static String[] businessPackages() {
            String[] packages = new String[ALL.length];
            for (int i = 0; i < ALL.length; i++) {
                packages[i] = "com.chessplatform." + ALL[i] + "..";
            }
            return packages;
        }

        private Modules() {
        }
    }
}
