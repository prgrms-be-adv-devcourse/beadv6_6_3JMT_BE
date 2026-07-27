package com.prompthub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

class RequestIdMdcAutoConfigurationTest {

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestIdMdcAutoConfiguration.class));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestIdMdcAutoConfiguration.class));

    @Test
    void 서블릿_웹_애플리케이션에서_최고_우선순위_필터를_등록한다() {
        webContextRunner.run(context -> {
            assertThat(context).hasBean("requestIdMdcFilterRegistration");

            FilterRegistrationBean<?> registration = context.getBean(
                    "requestIdMdcFilterRegistration", FilterRegistrationBean.class);

            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        });
    }

    @Test
    void 서블릿_웹_애플리케이션이_아니면_필터를_등록하지_않는다() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean("requestIdMdcFilterRegistration"));
    }
}
