package com.llmserving.membership;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * CLAUDE.md 8절 구조 규칙.
 */
class ArchitectureTest {

    private static final String BASE = "com.llmserving.membership";
    private static final String[] OTHER_APPS = {
            "com.llmserving.frontgateway..", "com.llmserving.worker..", "com.llmserving.broker..",
            "com.llmserving.modelmanager..", "com.llmserving.monitoring..", "com.llmserving.fakeaws..",
            "com.llmserving.client.."};
    /** EA의 입구 클래스. 새로 만든 얇은 입구가 아니므로 마지막 규칙에서 뺀다. */
    private static final String[] EA_ENTRY_CLASSES = {"__none__"};

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    void 다른_앱_모듈을_참조하지_않는다() {
        ArchRule rule = noClasses().that().resideInAPackage(BASE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(OTHER_APPS);
        rule.check(classes);
    }

    @Test
    void RestClient는_boundary_클래스에서만_쓴다() {
        ArchRule rule = noClasses().that().resideOutsideOfPackages(BASE + ".adapter..", BASE + ".config..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web.client..");
        rule.check(classes);
    }

    @Test
    void 새로_만든_얇은_REST_입구는_boundary_클래스를_직접_참조하지_않는다() {
        ArchRule rule = noClasses().that().areAnnotatedWith(RestController.class)
                .and().doNotHaveSimpleName(EA_ENTRY_CLASSES[0])
                .should().dependOnClassesThat().haveSimpleNameEndingWith("RestAdapter")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("Mock");
        rule.check(classes);
    }
}
