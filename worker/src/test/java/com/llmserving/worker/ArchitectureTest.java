package com.llmserving.worker;

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

    private static final String BASE = "com.llmserving.worker";
    private static final String[] OTHER_APPS = {
            "com.llmserving.frontgateway..", "com.llmserving.broker..", "com.llmserving.membership..",
            "com.llmserving.modelmanager..", "com.llmserving.monitoring..", "com.llmserving.fakeaws..",
            "com.llmserving.client.."};
    /** RestClient를 써도 되는 EA boundary 클래스. */
    private static final String EA_BOUNDARY = "StreamPublisher";

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
                .and().doNotHaveSimpleName(EA_BOUNDARY)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web.client..");
        rule.check(classes);
    }

    @Test
    void 새로_만든_얇은_REST_입구는_boundary_클래스를_직접_참조하지_않는다() {
        ArchRule rule = noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().haveSimpleNameEndingWith("RestAdapter")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("Mock")
                .orShould().dependOnClassesThat().haveSimpleName(EA_BOUNDARY);
        rule.check(classes);
    }
}
