/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.layout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttr;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.helpers.Transform;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MultiformatMessage;


/**
 * The output of the XMLLayout consists of a series of log4j:event
 * elements as defined in the <a href="log4j.dtd">log4j.dtd</a>. If configured to do so it will
 * output a complete well-formed XML file. The output is designed to be
 * included as an <em>external entity</em> in a separate file to form
 * a correct XML file.
 * <p/>
 * <p>For example, if <code>abc</code> is the name of the file where
 * the XMLLayout ouput goes, then a well-formed XML file would be:
 * <p/>
 * <pre>
 * &lt;?xml version="1.0" ?&gt;
 *
 * &lt;!DOCTYPE log4j:eventSet SYSTEM "log4j.dtd" [&lt;!ENTITY data SYSTEM "abc"&gt;]&gt;
 *
 * &lt;log4j:eventSet version="1.2" xmlns:log4j="http://logging.apache.org/log4j/"&gt;
 * &nbsp;&nbsp;&data;
 * &lt;/log4j:eventSet&gt;
 * </pre>
 * <p/>
 * <p>This approach enforces the independence of the XMLLayout and the
 * appender where it is embedded.
 * <p/>
 * <p>The <code>version</code> attribute helps components to correctly
 * intrepret output generated by XMLLayout. The value of this
 * attribute should be "1.1" for output generated by log4j versions
 * prior to log4j 1.2 (final release) and "1.2" for relase 1.2 and
 * later.
 * <p/>
 * Appenders using this layout should have their encoding
 * set to UTF-8 or UTF-16, otherwise events containing
 * non ASCII characters could result in corrupted
 * log files.
 */
@Plugin(name = "XMLLayout", type = "Core", elementType = "layout", printObject = true)
public class XMLLayout extends AbstractStringLayout {

    private static final int DEFAULT_SIZE = 256;

    private static final String[] FORMATS = new String[] {"xml"};

    private final boolean locationInfo;
    private final boolean properties;
    private final boolean complete;

    protected XMLLayout(final boolean locationInfo, final boolean properties, final boolean complete, final Charset charset) {
        super(charset);
        this.locationInfo = locationInfo;
        this.properties = properties;
        this.complete = complete;
    }

    /**
     * Formats a {@link org.apache.logging.log4j.core.LogEvent} in conformance with the log4j.dtd.
     *
     * @param event The LogEvent.
     * @return The XML representation of the LogEvent.
     */
    public String toSerializable(final LogEvent event) {
        final StringBuilder buf = new StringBuilder(DEFAULT_SIZE);

        // We yield to the \r\n heresy.

        buf.append("<log4j:event logger=\"");
        String name = event.getLoggerName();
        if (name.length() == 0) {
            name = "root";
        }
        buf.append(Transform.escapeTags(name));
        buf.append("\" timestamp=\"");
        buf.append(event.getMillis());
        buf.append("\" level=\"");
        buf.append(Transform.escapeTags(String.valueOf(event.getLevel())));
        buf.append("\" thread=\"");
        buf.append(Transform.escapeTags(event.getThreadName()));
        buf.append("\">\r\n");

        final Message msg = event.getMessage();
        if (msg != null) {
            boolean xmlSupported = false;
            if (msg instanceof MultiformatMessage) {
                final String[] formats = ((MultiformatMessage) msg).getFormats();
                for (final String format : formats) {
                    if (format.equalsIgnoreCase("XML")) {
                        xmlSupported = true;
                    }
                }
            }
            if (xmlSupported) {
                buf.append("<log4j:message>");
                buf.append(((MultiformatMessage) msg).getFormattedMessage(FORMATS));
                buf.append("</log4j:message>");
            } else {
                buf.append("<log4j:message><![CDATA[");
                // Append the rendered message. Also make sure to escape any
                // existing CDATA sections.
                Transform.appendEscapingCDATA(buf, event.getMessage().getFormattedMessage());
                buf.append("]]></log4j:message>\r\n");
            }
        }

        if (event.getContextStack().getDepth() > 0) {
            buf.append("<log4j:NDC><![CDATA[");
            Transform.appendEscapingCDATA(buf, event.getContextStack().toString());
            buf.append("]]></log4j:NDC>\r\n");
        }

        final Throwable throwable = event.getThrown();
        if (throwable != null) {
            final List<String> s = getThrowableString(throwable);
            buf.append("<log4j:throwable><![CDATA[");
            for (final String str : s) {
                Transform.appendEscapingCDATA(buf, str);
                buf.append("\r\n");
            }
            buf.append("]]></log4j:throwable>\r\n");
        }

        if (locationInfo) {
            final StackTraceElement element = event.getSource();
            buf.append("<log4j:locationInfo class=\"");
            buf.append(Transform.escapeTags(element.getClassName()));
            buf.append("\" method=\"");
            buf.append(Transform.escapeTags(element.getMethodName()));
            buf.append("\" file=\"");
            buf.append(Transform.escapeTags(element.getFileName()));
            buf.append("\" line=\"");
            buf.append(element.getLineNumber());
            buf.append("\"/>\r\n");
        }

        if (properties && event.getContextMap().size() > 0) {
            buf.append("<log4j:properties>\r\n");
            for (final Map.Entry<String, String> entry : event.getContextMap().entrySet()) {
                buf.append("<log4j:data name=\"");
                buf.append(Transform.escapeTags(entry.getKey()));
                buf.append("\" value=\"");
                buf.append(Transform.escapeTags(String.valueOf(entry.getValue())));
                buf.append("\"/>\r\n");
            }
            buf.append("</log4j:properties>\r\n");
        }

        buf.append("</log4j:event>\r\n\r\n");

        return buf.toString();
    }

    /**
     * Returns appropriate XML headers.
     * @return a byte array containing the header.
     */
    @Override
    public byte[] getHeader() {
        if (!complete) {
            return null;
        }
        final StringBuilder sbuf = new StringBuilder();
        sbuf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n");
        sbuf.append("<log4j:eventSet xmlns:log4j=\"http://logging.apache.org/log4j/\">\r\n");
        return sbuf.toString().getBytes(getCharset());
    }


    /**
     * Returns appropriate XML headers.
     * @return a byte array containing the footer.
     */
    @Override
    public byte[] getFooter() {
        if (!complete) {
            return null;
        }
        final StringBuilder sbuf = new StringBuilder();
        sbuf.append("</log4j:eventSet>\r\n");
        return sbuf.toString().getBytes(getCharset());
    }

    List<String> getThrowableString(final Throwable throwable) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw);
        try {
            throwable.printStackTrace(pw);
        } catch (final RuntimeException ex) {
            // Ignore any exceptions.
        }
        pw.flush();
        final LineNumberReader reader = new LineNumberReader(new StringReader(sw.toString()));
        final ArrayList<String> lines = new ArrayList<String>();
        try {
          String line = reader.readLine();
          while (line != null) {
            lines.add(line);
            line = reader.readLine();
          }
        } catch (final IOException ex) {
            if (ex instanceof InterruptedIOException) {
                Thread.currentThread().interrupt();
            }
            lines.add(ex.toString());
        }
        return lines;
    }

    /**
     * Create an XML Layout.
     * @param locationInfo If "true" include the location information in the generated XML.
     * @param properties If "true" include the thread context in the generated XML.
     * @param complete If "true" include the XML header.
     * @param charset The character set to use.
     * @return An XML Layout.
     */
    @PluginFactory
    public static XMLLayout createLayout(@PluginAttr("locationInfo") final String locationInfo,
                                         @PluginAttr("properties") final String properties,
                                         @PluginAttr("complete") final String complete,
                                         @PluginAttr("charset") final String charset) {
        Charset c = Charset.isSupported("UTF-8") ? Charset.forName("UTF-8") : Charset.defaultCharset();
        if (charset != null) {
            if (Charset.isSupported(charset)) {
                c = Charset.forName(charset);
            } else {
                LOGGER.error("Charset " + charset + " is not supported for layout, using " + c.displayName());
            }
        }
        final boolean info = locationInfo == null ? false : Boolean.valueOf(locationInfo);
        final boolean props = properties == null ? false : Boolean.valueOf(properties);
        final boolean comp = complete == null ? false : Boolean.valueOf(complete);
        return new XMLLayout(info, props, comp, c);
    }
}
