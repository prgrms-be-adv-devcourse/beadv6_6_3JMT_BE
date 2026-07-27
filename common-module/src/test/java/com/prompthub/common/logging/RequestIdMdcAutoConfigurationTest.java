package com.prompthub.common.logging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdMdcAutoConfigurationTest {

    @Test
    void registersTheFilterForServletApplicationsOnly() {
        new WebApplicationContextRunner()
            .withUserConfiguration(RequestIdMdcAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
                    .isInstanceOf(RequestIdMdcFilter.class);
            });
    }

    @Test
    void doesNotRegisterTheFilterForReactiveApplications() {
        new ReactiveWebApplicationContextRunner()
            .withUserConfiguration(RequestIdMdcAutoConfiguration.class)
            .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }
}
