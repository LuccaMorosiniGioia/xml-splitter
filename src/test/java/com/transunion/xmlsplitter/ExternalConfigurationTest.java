package com.transunion.xmlsplitter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.transunion.xmlsplitter.cli.SplitterProperties;

@SpringBootTest(args = {"--help", "--spring.config.additional-location=file:src/test/config/external-splitter.yml"})
class ExternalConfigurationTest {

    @Autowired
    SplitterProperties properties;

    @Test
    void anExternalYamlOverridesTheBundledDefaults() {
        assertThat(properties.maxTransactions()).isEqualTo(77);
        assertThat(properties.headerMode()).isEqualTo("first-only");
        assertThat(properties.trailerMode()).isEqualTo("last-file");
    }
}
