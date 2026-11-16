package com.transunion.xmlsplitter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import com.transunion.xmlsplitter.core.XmlSplitService;

@SpringBootApplication
@ConfigurationPropertiesScan
public class XmlSplitterApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(XmlSplitterApplication.class, args)));
    }

    /** The core stays free of Spring annotations; it is wired here. */
    @Bean
    XmlSplitService xmlSplitService() {
        return new XmlSplitService();
    }
}
