package com.ocr;

import com.ocr.application.command.StartPiDataExtractionJobHandler;
import com.ocr.shared.pi.PiExtractionNormalizer;
import com.ocr.v2.pipeline.ExtractionPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code lite} profile is the upgrade path for existing v1-only deployments: take the new jar,
 * do not stand up PostgreSQL, keep serving exactly what you served before.
 *
 * <p>This test is the guarantee that it works - it starts the whole application with no database
 * reachable at all, which would fail immediately if any v2 bean leaked into the profile.
 */
@SpringBootTest
@ActiveProfiles("lite")
class LiteProfileTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("the application starts with no database")
    void startsWithoutDatabase() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("v1 still works, including the shared normaliser")
    void v1IsIntact() {
        assertThat(context.getBean(StartPiDataExtractionJobHandler.class)).isNotNull();
        assertThat(context.getBean(PiExtractionNormalizer.class)).isNotNull();
    }

    @Test
    @DisplayName("no v2 bean is defined, so nothing asks for a datasource")
    void v2IsAbsent() {
        assertThat(context.getBeanNamesForType(ExtractionPipeline.class)).isEmpty();
        assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
    }
}
