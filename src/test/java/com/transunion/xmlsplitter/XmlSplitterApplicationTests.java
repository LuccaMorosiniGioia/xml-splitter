package com.transunion.xmlsplitter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.transunion.xmlsplitter.cli.SplitCommand;
import com.transunion.xmlsplitter.cli.SplitterProperties;

@SpringBootTest(args = {"--help", "--splitter.max-transactions=123"})
class XmlSplitterApplicationTests {

    @Autowired
    SplitCommand command;

    @Autowired
    SplitterProperties properties;

    @Test
    void contextLoadsAndBindsApplicationYml() {
        assertThat(command.getExitCode()).isZero();
        assertThat(properties.maxTransactions()).isEqualTo(123);
        assertThat(properties.splitElement()).isEqualTo("Policy");
        assertThat(properties.headerMode()).isEqualTo("every-file");
    }
}
