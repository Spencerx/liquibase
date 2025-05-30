package liquibase.serializer.core.xml;

import liquibase.change.ColumnConfig;
import liquibase.change.ConstraintsConfig;
import liquibase.changelog.ChangeLogChild;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.GlobalConfiguration;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.parser.NamespaceDetails;
import liquibase.parser.NamespaceDetailsFactory;
import liquibase.parser.core.xml.LiquibaseEntityResolver;
import liquibase.serializer.ChangeLogSerializer;
import liquibase.serializer.LiquibaseSerializable;
import liquibase.serializer.LiquibaseSerializable.SerializationType;
import liquibase.util.ISODateFormat;
import liquibase.util.StreamUtil;
import liquibase.util.XMLUtil;
import liquibase.util.xml.DefaultXmlWriter;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class XMLChangeLogSerializer implements ChangeLogSerializer {

    public static final String INVALID_STRING_ENCODING_MESSAGE = "Invalid string encoding";
    private Document currentChangeLogFileDOM;

    private static final String XML_VERSION = "1.1";
    private final LiquibaseEntityResolver resolver = new LiquibaseEntityResolver();

    public XMLChangeLogSerializer() {
        try {
            this.currentChangeLogFileDOM = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            this.currentChangeLogFileDOM.setXmlVersion(XML_VERSION);
        } catch (ParserConfigurationException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    protected XMLChangeLogSerializer(Document currentChangeLogFileDOM) {
        this.currentChangeLogFileDOM = currentChangeLogFileDOM;
    }

    public void setCurrentChangeLogFileDOM(Document currentChangeLogFileDOM) {
        this.currentChangeLogFileDOM = currentChangeLogFileDOM;
    }

    @Override
    public String[] getValidFileExtensions() {
        return new String[]{"xml"};
    }


    public String serialize(DatabaseChangeLog databaseChangeLog) {
        return null; //todo
    }

    @Override
    public String serialize(LiquibaseSerializable object, boolean pretty) {
        StringBuilder buffer = new StringBuilder();
        int indent = -1;
        if (pretty) {
            indent = 0;
        }
        nodeToStringBuilder(createNode(object), buffer, indent);
        return buffer.toString();
    }

    @Override
    public <T extends ChangeLogChild> void write(List<T> children, OutputStream out) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder documentBuilder;
        try {
            documentBuilder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        documentBuilder.setEntityResolver(resolver);

        Document doc = documentBuilder.newDocument();
        doc.setXmlVersion(XML_VERSION);
        Element changeLogElement = doc.createElementNS(LiquibaseSerializable.STANDARD_CHANGELOG_NAMESPACE, "databaseChangeLog");

        changeLogElement.setAttribute("xmlns", LiquibaseSerializable.STANDARD_CHANGELOG_NAMESPACE);
        changeLogElement.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");

        Map<String, String> shortNameByNamespace = new HashMap<>();
        Map<String, String> urlByNamespace = new HashMap<>();

        for (NamespaceDetails details : NamespaceDetailsFactory.getInstance().getNamespaceDetails()) {
            for (String namespace : details.getNamespaces()) {
                if (details.getPriority() > 0 && details.supports(this, namespace)) {
                    String shortName = details.getShortName(namespace);
                    String url = details.getSchemaUrl(namespace);
                    if (shortName != null) {
                        shortNameByNamespace.put(namespace, shortName);
                    }
                    if (url != null) {
                        urlByNamespace.put(namespace, url);
                    }
                }
            }
        }

        for (Map.Entry<String, String> entry : shortNameByNamespace.entrySet()) {
            if (StringUtils.isNotEmpty(entry.getValue())) {
                changeLogElement.setAttribute("xmlns:" + entry.getValue(), entry.getKey());
            }
        }


        StringBuilder schemaLocationAttribute = new StringBuilder();
        for (Map.Entry<String, String> entry : urlByNamespace.entrySet()) {
            if (StringUtils.isNotEmpty(entry.getValue())) {
                schemaLocationAttribute
                    .append(entry.getKey())
                    .append(" ")
                    .append(entry.getValue())
                    .append(" ");
            }
        }

        changeLogElement.setAttribute("xsi:schemaLocation", schemaLocationAttribute.toString().trim());

        doc.appendChild(changeLogElement);
        setCurrentChangeLogFileDOM(doc);

        for (T child : children) {
            doc.getDocumentElement().appendChild(createNode(child));
        }

        new DefaultXmlWriter().write(doc, out);
    }

    @Override
    public void append(ChangeSet changeSet, File changeLogFile) throws IOException {
        String existingChangeLog;
        try (InputStream in = Files.newInputStream(changeLogFile.toPath())) {
            existingChangeLog = StreamUtil.readStreamAsString(in);
        }

        try (OutputStream out = Files.newOutputStream(changeLogFile.toPath())) {
            if (!existingChangeLog.contains("</databaseChangeLog>")) {
                write(Collections.singletonList(changeSet), out);
            } else {
                existingChangeLog = existingChangeLog.replaceFirst("</databaseChangeLog>", serialize(changeSet, true) + "\n</databaseChangeLog>");

                StreamUtil.copy(new ByteArrayInputStream(existingChangeLog.getBytes(GlobalConfiguration.OUTPUT_FILE_ENCODING.getCurrentValue())), out);
            }
            out.flush();
        }
    }

    public Element createNode(LiquibaseSerializable object) {
        String namespace = object.getSerializedObjectNamespace();

        String nodeName = object.getSerializedObjectName();

        NamespaceDetails details = NamespaceDetailsFactory.getInstance().getNamespaceDetails(this, namespace);
        if ((details != null) && !details.getShortName(namespace).isEmpty()) {
            nodeName = details.getShortName(namespace) + ":" + nodeName;
        }
        Element node = currentChangeLogFileDOM.createElementNS(namespace, nodeName);

        try {
            for (String field : object.getSerializableFields()) {
                setValueOnNode(node, object.getSerializableFieldNamespace(field), field, object.getSerializableFieldValue(field), object.getSerializableFieldType(field), namespace);
            }
        } catch (UnexpectedLiquibaseException e) {
            if (object instanceof ChangeSet && e.getMessage().startsWith(INVALID_STRING_ENCODING_MESSAGE)) {
                throw new UnexpectedLiquibaseException(e.getMessage() + " in changeSet " + ((ChangeSet) object).toString(false) + ". To resolve, remove the invalid character on the database and try again");
            }
            throw e;
        }

        return node;
    }

    private void setValueOnNode(Element node, String objectNamespace, String objectName, Object value, LiquibaseSerializable.SerializationType serializationType, String parentNamespace) {
        if (value == null) {
            return;
        }

        if (value instanceof Collection) {
            for (Object child : (Collection) value) {
                setValueOnNode(node, objectNamespace, objectName, child, serializationType, parentNamespace);
            }
        } else if (value instanceof Map) {
            for (Map.Entry entry : (Set<Map.Entry>) ((Map) value).entrySet()) {
                Element mapNode = currentChangeLogFileDOM.createElementNS(LiquibaseSerializable.STANDARD_CHANGELOG_NAMESPACE, qualifyName(objectName, objectNamespace, parentNamespace));
                if (serializationType == SerializationType.NESTED_OBJECT) {
                    setValueOnNode(mapNode, objectNamespace, (String) entry.getKey(), entry.getValue(), serializationType, objectNamespace);
                } else {
                    setValueOnNode(mapNode, objectNamespace, "name", entry.getKey(), SerializationType.NAMED_FIELD, objectNamespace);
                    setValueOnNode(mapNode, objectNamespace, "value", entry.getValue(), serializationType, objectNamespace);
                }
                node.appendChild(mapNode);
            }
        } else if (value instanceof LiquibaseSerializable) {
            node.appendChild(createNode((LiquibaseSerializable) value));
        } else if (value instanceof Object[]) {
            if (serializationType.equals(LiquibaseSerializable.SerializationType.NESTED_OBJECT)) {
                String namespace = LiquibaseSerializable.STANDARD_CHANGELOG_NAMESPACE;
                Element newNode = createNode(namespace, objectName, "");
                for (Object child : (Object[]) value) {
                    setValueOnNode(newNode, namespace, objectName, child, serializationType, parentNamespace);
                }
                node.appendChild(newNode);
            } else {
                for (Object child : (Object[]) value) {
                    setValueOnNode(node, objectNamespace, objectName, child, serializationType, parentNamespace);
                }
            }
        } else {
            if (serializationType.equals(LiquibaseSerializable.SerializationType.NESTED_OBJECT)) {
                String namespace = LiquibaseSerializable.STANDARD_CHANGELOG_NAMESPACE;
                node.appendChild(createNode(namespace, objectName, value.toString()));
            } else if (serializationType.equals(LiquibaseSerializable.SerializationType.DIRECT_VALUE)) {
                try {
                    node.setTextContent(checkString(value.toString()));
                } catch (UnexpectedLiquibaseException e) {
                    if (e.getMessage().startsWith(INVALID_STRING_ENCODING_MESSAGE)) {
                        throw new UnexpectedLiquibaseException(e.getMessage() + " in text of " + node.getTagName() + ". To resolve, remove the invalid character on the database and try again");
                    }
                }
            } else {
                String attributeName = qualifyName(objectName, objectNamespace, parentNamespace);
                try {
                    node.setAttribute(attributeName, checkString(value.toString()));
                } catch (UnexpectedLiquibaseException e) {
                    if (e.getMessage().startsWith(INVALID_STRING_ENCODING_MESSAGE)) {
                        throw new UnexpectedLiquibaseException(e.getMessage() + " on " + node.getTagName() + "." + attributeName + ". To resolve, remove the invalid character on the database and try again");
                    }
                }
            }
        }
    }

    /**
     * Catch any characters that will cause problems when parsing the XML down the road.
     *
     * @throws UnexpectedLiquibaseException with the message {@link #INVALID_STRING_ENCODING_MESSAGE} if an issue is found.
     */
    protected String checkString(String text) throws UnexpectedLiquibaseException {
        if (null == text || text.isEmpty()) {
            return text;
        }

        final int len = text.length();
        char current;
        int codePoint;

        for (int i = 0; i < len; i++) {
            current = text.charAt(i);
            if (Character.isHighSurrogate(current) && i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
                codePoint = text.codePointAt(i++);
            } else {
                codePoint = current;
            }
            if ((codePoint == '\n')
                    || (codePoint == '\r')
                    || (codePoint == '\t')
                    || (codePoint == 0xB)
                    || (codePoint == 0xC)
                    || ((codePoint >= 0x20) && (codePoint <= 0x7E))
                    || ((codePoint >= 0xA0) && (codePoint <= 0xD7FF))
                    || ((codePoint >= 0xE000) && (codePoint <= 0xFFFD))
                    || ((codePoint >= 0x10000) && (codePoint <= 0x10FFFF))
                    ) {
                //ok
            } else {
                throw new UnexpectedLiquibaseException(INVALID_STRING_ENCODING_MESSAGE + " with codePoint " + codePoint);
            }
        }

        return text;
    }

    private String qualifyName(String objectName, String objectNamespace, String parentNamespace) {
        if ((objectNamespace != null) && !objectNamespace.equals(LiquibaseSerializable.STANDARD_CHANGELOG_NAMESPACE)
            && !objectNamespace.equals(parentNamespace)) {
            NamespaceDetails details = NamespaceDetailsFactory.getInstance().getNamespaceDetails(this, objectNamespace);
            return details.getShortName(objectNamespace) + ":" + objectName;
        } else {
            return objectName;
        }
    }


    // create a XML node with nodeName and simple text content
    public Element createNode(String nodeNamespace, String nodeName, String nodeContent) {
        Element element = currentChangeLogFileDOM.createElementNS(nodeNamespace, nodeName);
        element.setTextContent(nodeContent);
        return element;
    }

    public Element createNode(ColumnConfig columnConfig) {
        Element element = currentChangeLogFileDOM.createElementNS(columnConfig.getSerializedObjectNamespace(), "column");
        if (columnConfig.getName() != null) {
            element.setAttribute("name", columnConfig.getName());
        }
        if (columnConfig.getType() != null) {
            element.setAttribute("type", columnConfig.getType());
        }

        if (columnConfig.getDefaultValue() != null) {
            element.setAttribute("defaultValue", columnConfig.getDefaultValue());
        }
        if (columnConfig.getDefaultValueNumeric() != null) {
            element.setAttribute("defaultValueNumeric", columnConfig.getDefaultValueNumeric().toString());
        }
        if (columnConfig.getDefaultValueDate() != null) {
            element.setAttribute("defaultValueDate", new ISODateFormat().format(columnConfig.getDefaultValueDate()));
        }
        if (columnConfig.getDefaultValueBoolean() != null) {
            element.setAttribute("defaultValueBoolean", columnConfig.getDefaultValueBoolean().toString());
        }
        if (columnConfig.getDefaultValueComputed() != null) {
            element.setAttribute("defaultValueComputed", columnConfig.getDefaultValueComputed().toString());
        }
        if (columnConfig.getDefaultValueSequenceNext() != null) {
            element.setAttribute("defaultValueSequenceNext", columnConfig.getDefaultValueSequenceNext().toString());
        }
        if (columnConfig.getValue() != null) {
            element.setAttribute("value", columnConfig.getValue());
        }
        if (columnConfig.getValueNumeric() != null) {
            element.setAttribute("valueNumeric", columnConfig.getValueNumeric().toString());
        }
        if (columnConfig.getValueBoolean() != null) {
            element.setAttribute("valueBoolean", columnConfig.getValueBoolean().toString());
        }
        if (columnConfig.getValueDate() != null) {
            element.setAttribute("valueDate", new ISODateFormat().format(columnConfig.getValueDate()));
        }
        if (columnConfig.getValueComputed() != null) {
            element.setAttribute("valueComputed", columnConfig.getValueComputed().toString());
        }
        if (columnConfig.getValueSequenceNext() != null) {
            element.setAttribute("valueSequenceNext", columnConfig.getValueSequenceNext().toString());
        }
        if (columnConfig.getValueSequenceCurrent() != null) {
            element.setAttribute("valueSequenceNext", columnConfig.getValueSequenceCurrent().toString());
        }
        if (StringUtils.trimToNull(columnConfig.getRemarks()) != null) {
            element.setAttribute("remarks", columnConfig.getRemarks());
        }

        if ((columnConfig.isAutoIncrement() != null) && columnConfig.isAutoIncrement()) {
            element.setAttribute("autoIncrement", "true");
        }

        ConstraintsConfig constraints = columnConfig.getConstraints();
        if (constraints != null) {
            Element constraintsElement = currentChangeLogFileDOM.createElementNS(columnConfig.getSerializedObjectNamespace(), "constraints");
            if (constraints.getCheckConstraint() != null) {
                constraintsElement.setAttribute("checkConstraint", constraints.getCheckConstraint());
            }
            if (constraints.getForeignKeyName() != null) {
                constraintsElement.setAttribute("foreignKeyName", constraints.getForeignKeyName());
            }
            if (constraints.getReferences() != null) {
                constraintsElement.setAttribute("references", constraints.getReferences());
            }
            if (constraints.getReferencedTableName() != null) {
                constraintsElement.setAttribute("referencedTableName", constraints.getReferencedTableName());
            }
            if (constraints.getReferencedColumnNames() != null) {
                constraintsElement.setAttribute("referencedColumnNames", constraints.getReferencedColumnNames());
            }
            if (constraints.isDeferrable() != null) {
                constraintsElement.setAttribute("deferrable", constraints.isDeferrable().toString());
            }
            if (constraints.getValidateNullable() != null) {
                constraintsElement.setAttribute("validateNullable", constraints.getValidateNullable().toString());
            }
            if (constraints.getValidateUnique() != null) {
                constraintsElement.setAttribute("validateUnique", constraints.getValidateUnique().toString());
            }
            if (constraints.getValidatePrimaryKey() != null) {
                constraintsElement.setAttribute("validatePrimaryKey", constraints.getValidatePrimaryKey().toString());
            }
            if (constraints.getValidateForeignKey() != null) {
                constraintsElement.setAttribute("validateForeignKey", constraints.getValidateForeignKey().toString());
            }
            if (constraints.isDeleteCascade() != null) {
                constraintsElement.setAttribute("deleteCascade", constraints.isDeleteCascade().toString());
            }
            if (constraints.isInitiallyDeferred() != null) {
                constraintsElement.setAttribute("initiallyDeferred", constraints.isInitiallyDeferred().toString());
            }
            if (constraints.isNullable() != null) {
                constraintsElement.setAttribute("nullable", constraints.isNullable().toString());
            }
            if (constraints.isPrimaryKey() != null) {
                constraintsElement.setAttribute("primaryKey", constraints.isPrimaryKey().toString());
            }
            if (constraints.isUnique() != null) {
                constraintsElement.setAttribute("unique", constraints.isUnique().toString());
            }
            if (constraints.getUniqueConstraintName() != null) {
                constraintsElement.setAttribute("uniqueConstraintName", constraints.getUniqueConstraintName());
            }
            if (constraints.getPrimaryKeyName() != null) {
                constraintsElement.setAttribute("primaryKeyName", constraints.getPrimaryKeyName());
            }
            if (constraints.getPrimaryKeyTablespace() != null) {
                constraintsElement.setAttribute("primaryKeyTablespace", constraints.getPrimaryKeyTablespace());
            }
            if (constraints.getNotNullConstraintName() != null) {
                constraintsElement.setAttribute("notNullConstraintName", constraints.getNotNullConstraintName());
            }
            if (constraints.getReferencedTableCatalogName() != null) {
                constraintsElement.setAttribute("referencedTableCatalogName", constraints.getReferencedTableCatalogName());
            }
            if (constraints.getReferencedTableSchemaName() != null) {
                constraintsElement.setAttribute("referencedTableSchemaName", constraints.getReferencedTableSchemaName());
            }
            element.appendChild(constraintsElement);
        }

        return element;
    }

    /*
     * Creates a {@link String} using the XML element representation of this
     * change
     *
     * @param node the {@link Element} associated to this change
     * @param buffer a {@link StringBuilder} object used to hold the {@link String}
     *               representation of the change
     */
    private void nodeToStringBuilder(Node node, StringBuilder buffer, int indent) {
        if (indent >= 0) {
            if (indent > 0) {
                buffer.append("\n");
            }
            buffer.append(StringUtils.repeat(" ", indent));
        }
        buffer.append("<").append(node.getNodeName());
        SortedMap<String, String> attributeMap = new TreeMap<>();
        NamedNodeMap attributes = node.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            attributeMap.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        boolean firstAttribute = true;
        for (Map.Entry entry : attributeMap.entrySet()) {
            String value = (String) entry.getValue();
            if (value != null) {
                if ((indent >= 0) && !firstAttribute && (attributeMap.size() > 2)) {
                    buffer.append("\n").append(StringUtils.repeat(" ", indent)).append("        ");
                } else {
                    buffer.append(" ");
                }
                buffer.append(entry.getKey()).append("=\"").append(value).append("\"");
                firstAttribute = false;
            }
        }
        String textContent = StringUtils.trimToEmpty(XMLUtil.getTextContent(node));
        textContent = escapeXml(textContent);
        buffer.append(">").append(textContent);

        boolean sawChildren = false;
        NodeList childNodes = node.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node childNode = childNodes.item(i);
            if (childNode instanceof Element) {
                int newIndent = indent;
                if (newIndent >= 0) {
                    newIndent += 4;
                }
                nodeToStringBuilder(childNode, buffer, newIndent);
                sawChildren = true;
            }
        }
        if (indent >= 0) {
            if (sawChildren) {
                buffer.append("\n").append(StringUtils.repeat(" ", indent));
            }
        }

        if (!sawChildren && textContent.isEmpty()) {
            buffer.replace(buffer.length() - 1, buffer.length(), "/>");
        } else {
            buffer.append("</").append(node.getNodeName()).append(">");
        }
    }

    @Override
    public int getPriority() {
        return PRIORITY_DEFAULT;
    }

    /**
     * Provided as a way for sub-classes to override and be able to convert a string
     * that might have XML reserved characters to an XML-escaped version of that string.
     */
    public String escapeXml(String valueToEscape) {
        return valueToEscape;
    }

}
