package com.transunion.xmlsplitter.core.xml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.transunion.xmlsplitter.core.SplitException;

/**
 * Optional XSD validation of every output part. Validation is SAX based, so it streams like the rest.
 */
public final class SchemaValidator {

    private final Path schemaFile;
    private final Schema schema;

    private SchemaValidator(Path schemaFile, Schema schema) {
        this.schemaFile = schemaFile;
        this.schema = schema;
    }

    public static SchemaValidator load(Path schemaFile) {
        if (!Files.isReadable(schemaFile)) {
            throw new SplitException(SplitException.Reason.INVALID_ARGUMENTS, "Schema not found: " + schemaFile);
        }
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            // Local imports/includes next to the XSD are allowed; nothing is fetched from the network.
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            return new SchemaValidator(schemaFile, factory.newSchema(schemaFile.toFile()));
        } catch (SAXException e) {
            throw new SplitException(SplitException.Reason.INVALID_ARGUMENTS,
                    "Cannot load schema " + schemaFile + ": " + e.getMessage(), e);
        }
    }

    public void validate(Path part) {
        try {
            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new StreamSource(part.toFile()));
        } catch (SAXParseException e) {
            throw new SplitException(SplitException.Reason.INVALID_OUTPUT, "Output " + part.getFileName()
                    + " does not match " + schemaFile.getFileName() + " at line " + e.getLineNumber() + ": "
                    + e.getMessage(), e);
        } catch (SAXException e) {
            throw new SplitException(SplitException.Reason.INVALID_OUTPUT, "Output " + part.getFileName()
                    + " does not match " + schemaFile.getFileName() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new SplitException(SplitException.Reason.IO_FAILURE, "Cannot read " + part, e);
        }
    }
}
