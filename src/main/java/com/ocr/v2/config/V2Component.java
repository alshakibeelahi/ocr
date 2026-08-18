package com.ocr.v2.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A component belonging to the v2 stack.
 *
 * <p>Equivalent to {@code @Component}, except that it disappears under the {@code lite} profile.
 * That profile exists so an existing v1-only deployment can take the new jar without standing up
 * PostgreSQL: under {@code lite} the database auto-configuration is excluded and every v2 bean -
 * which would need a datasource - is simply not defined, leaving v1 exactly as it was.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Profile("!lite")
public @interface V2Component {

    /** Bean name, passed through to {@link Component#value()}. */
    String value() default "";
}
