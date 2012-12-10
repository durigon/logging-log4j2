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
package org.apache.logging.log4j;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.simple.SimpleLoggerContextFactory;
import org.apache.logging.log4j.spi.LoggerContext;
import org.apache.logging.log4j.spi.LoggerContextFactory;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.PropsUtil;

/**
 * The anchor point for the logging system.
 */
public class LogManager {
    /**
     * The name of the root Logger.
     */
    public static final String ROOT_LOGGER_NAME = "";

    private static final String LOGGER_RESOURCE = "META-INF/log4j-provider.properties";
    private static final String LOGGER_CONTEXT_FACTORY = "LoggerContextFactory";
    private static final String API_VERSION = "Log4jAPIVersion";
    private static final String FACTORY_PRIORITY = "FactoryPriority";
    private static final String[] COMPATIBLE_API_VERSIONS = {
        "2.0.0"
    };

    private static final String FACTORY_PROPERTY_NAME = "log4j2.loggerContextFactory";

    private static LoggerContextFactory factory;

    private static final Logger logger = StatusLogger.getLogger();

    /**
     * Prevents instantiation
     */
    protected LogManager() {
    }

    /**
     * Scans the classpath to find all logging implementation. Currently, only one will
     * be used but this could be extended to allow multiple implementations to be used.
     */
    static {
        // Shortcut binding to force a specific logging implementation.
        final PropsUtil managerProps = new PropsUtil("log4j2.LogManager.properties");
        final String factoryClass = managerProps.getStringProperty(FACTORY_PROPERTY_NAME);
        final ClassLoader cl = findClassLoader();
        if (factoryClass != null) {
            try {
                final Class<?> clazz = cl.loadClass(factoryClass);
                if (LoggerContextFactory.class.isAssignableFrom(clazz)) {
                    factory = (LoggerContextFactory) clazz.newInstance();
                }
            } catch (final ClassNotFoundException cnfe) {
                logger.error("Unable to locate configured LoggerContextFactory {}", factoryClass);
            } catch (final Exception ex) {
                logger.error("Unable to create configured LoggerContextFactory {}", factoryClass, ex);
            }
        }

        if (factory == null) {
            final SortedMap<Integer, LoggerContextFactory> factories = new TreeMap<Integer, LoggerContextFactory>();

            Enumeration<URL> enumResources = null;
            try {
                enumResources = cl.getResources(LOGGER_RESOURCE);
            } catch (final IOException e) {
                logger.fatal("Unable to locate " + LOGGER_RESOURCE, e);
            }

            if (enumResources != null) {
                while (enumResources.hasMoreElements()) {
                    final Properties props = new Properties();
                    final URL url = enumResources.nextElement();
                    try {
                        props.load(url.openStream());
                    } catch (final IOException ioe) {
                        logger.error("Unable to read " + url.toString(), ioe);
                    }
                    if (!validVersion(props.getProperty(API_VERSION))) {
                        continue;
                    }
                    final String weight = props.getProperty(FACTORY_PRIORITY);
                    final Integer priority = weight == null ? -1 : Integer.valueOf(weight);
                    final String className = props.getProperty(LOGGER_CONTEXT_FACTORY);
                    if (className != null) {
                        try {
                            final Class<?> clazz = cl.loadClass(className);
                            if (LoggerContextFactory.class.isAssignableFrom(clazz)) {
                                factories.put(priority, (LoggerContextFactory) clazz.newInstance());
                            } else {
                                logger.error(className + " does not implement " + LoggerContextFactory.class.getName());
                            }
                        } catch (final ClassNotFoundException cnfe) {
                            logger.error("Unable to locate class " + className + " specified in " + url.toString(),
                                cnfe);
                        } catch (final IllegalAccessException iae) {
                            logger.error("Unable to create class " + className + " specified in " + url.toString(),
                                iae);
                        } catch (final Exception e) {
                            logger.error("Unable to create class " + className + " specified in " + url.toString(), e);
                            e.printStackTrace();
                        }
                    }
                }
                if (factories.size() == 0) {
                    logger.error("Unable to locate a logging implementation, using SimpleLogger");
                    factory = new SimpleLoggerContextFactory();
                } else {
                    final StringBuilder sb = new StringBuilder("Multiple logging implementations found: \n");
                    for (final Map.Entry<Integer, LoggerContextFactory> entry : factories.entrySet()) {
                        sb.append("Factory: ").append(entry.getValue().getClass().getName());
                        sb.append(", Weighting: ").append(entry.getKey()).append("\n");
                    }
                    factory = factories.get(factories.lastKey());
                    sb.append("Using factory: ").append(factory.getClass().getName());
                    logger.warn(sb.toString());

                }
            } else {
                logger.error("Unable to locate a logging implementation, using SimpleLogger");
                factory = new SimpleLoggerContextFactory();
            }
        }
    }

    /**
     * Returns the LoggerContextFactory.
     * @return The LoggerContextFactory.
     */
    public static LoggerContextFactory getFactory() {
        return factory;
    }

    /**
     * Returns a Logger with the specified name.
     *
     * @param name The logger name.
     * @return The Logger.
     */
    public static Logger getLogger(final String name) {
        return factory.getContext(LogManager.class.getName(), null, false).getLogger(name);
    }

    /**
     * Returns a Logger with the specified name.
     *
     * @param name The logger name.
     * @param messageFactory The message factory is used only when creating a logger, subsequent use does not change the logger but will log a warning if mismatched.
     * @return The Logger.
     */
    public static Logger getLogger(final String name, final MessageFactory messageFactory) {
        return factory.getContext(LogManager.class.getName(), null, false).getLogger(name, messageFactory);
    }

    /**
     * Returns a Logger using the fully qualified name of the Class as the Logger name.
     * @param clazz The Class whose name should be used as the Logger name.
     * @return The Logger.
     */
    public static Logger getLogger(final Class<?> clazz) {
        return getLogger(clazz != null ? clazz.getName() : null);
    }

    /**
     * Returns a Logger using the fully qualified name of the Class as the Logger name.
     * @param clazz The Class whose name should be used as the Logger name.
     * @param messageFactory The message factory is used only when creating a logger, subsequent use does not change the logger but will log a warning if mismatched.
     * @return The Logger.
     */
    public static Logger getLogger(final Class<?> clazz, final MessageFactory messageFactory) {
        return getLogger(clazz != null ? clazz.getName() : null, messageFactory);
    }

    /**
     * Returns a Logger using the fully qualified class name of the value as the Logger name.
     * @param value The value whose class name should be used as the Logger name.
     * @return The Logger.
     */
    public static Logger getLogger(final Object value) {
        return getLogger(value != null ? value.getClass() : null);
    }

    /**
     * Returns a Logger using the fully qualified class name of the value as the Logger name.
     * @param value The value whose class name should be used as the Logger name.
     * @param messageFactory The message factory is used only when creating a logger, subsequent use does not change the logger but will log a warning if mismatched.
     * @return The Logger.
     */
    public static Logger getLogger(final Object value, final MessageFactory messageFactory) {
        return getLogger(value != null ? value.getClass() : null, messageFactory);
    }

    /**
     * Returns a Logger with the specified name.
     *
     * @param fqcn The fully qualified class name of the class that this method is a member of.
     * @param name The logger name.
     * @return The Logger.
     */
    protected static Logger getLogger(final String fqcn, final String name) {
        return factory.getContext(fqcn, null, false).getLogger(name);
    }

    /**
     * Returns the current LoggerContext.
     * <p>
     * WARNING - The LoggerContext returned by this method may not be the LoggerContext used to create a Logger
     * for the calling class.
     * @return  The current LoggerContext.
     */
    public static LoggerContext getContext() {
        return factory.getContext(LogManager.class.getName(), null, true);
    }

    /**
     * Returns a LoggerContext.
     *
     * @param currentContext if false the LoggerContext appropriate for the caller of this method is returned. For
     * example, in a web application if the caller is a class in WEB-INF/lib then one LoggerContext may be
     * returned and if the caller is a class in the container's classpath then a different LoggerContext may be
     * returned. If true then only a single LoggerContext will be returned.
     * @return a LoggerContext.
     */
    public static LoggerContext getContext(final boolean currentContext) {
        return factory.getContext(LogManager.class.getName(), null, currentContext);
    }

    /**
     * Returns a LoggerContext.
     *
     * @param loader The ClassLoader for the context. If null the context will attempt to determine the appropriate
     * ClassLoader.
     * @param currentContext if false the LoggerContext appropriate for the caller of this method is returned. For
     * example, in a web application if the caller is a class in WEB-INF/lib then one LoggerContext may be
     * returned and if the caller is a class in the container's classpath then a different LoggerContext may be
     * returned. If true then only a single LoggerContext will be returned.
     * @return a LoggerContext.
     */
    public static LoggerContext getContext(final ClassLoader loader, final boolean currentContext) {
        return factory.getContext(LogManager.class.getName(), loader, currentContext);
    }


    /**
     * Returns a LoggerContext
     * @param fqcn The fully qualified class name of the Class that this method is a member of.
     * @param currentContext if false the LoggerContext appropriate for the caller of this method is returned. For
     * example, in a web application if the caller is a class in WEB-INF/lib then one LoggerContext may be
     * returned and if the caller is a class in the container's classpath then a different LoggerContext may be
     * returned. If true then only a single LoggerContext will be returned.
     * @return a LoggerContext.
     */
    protected static LoggerContext getContext(final String fqcn, final boolean currentContext) {
        return factory.getContext(fqcn, null, currentContext);
    }


    /**
     * Returns a LoggerContext
     * @param fqcn The fully qualified class name of the Class that this method is a member of.
     * @param loader The ClassLoader for the context. If null the context will attempt to determine the appropriate
     * ClassLoader.
     * @param currentContext if false the LoggerContext appropriate for the caller of this method is returned. For
     * example, in a web application if the caller is a class in WEB-INF/lib then one LoggerContext may be
     * returned and if the caller is a class in the container's classpath then a different LoggerContext may be
     * returned. If true then only a single LoggerContext will be returned.
     * @return a LoggerContext.
     */
    protected static LoggerContext getContext(final String fqcn, final ClassLoader loader, final boolean currentContext) {
        return factory.getContext(fqcn, loader, currentContext);
    }

    private static ClassLoader findClassLoader() {
        ClassLoader cl;
        if (System.getSecurityManager() == null) {
            cl = Thread.currentThread().getContextClassLoader();
        } else {
            cl = java.security.AccessController.doPrivileged(
                new java.security.PrivilegedAction<ClassLoader>() {
                    public ClassLoader run() {
                        return Thread.currentThread().getContextClassLoader();
                    }
                }
            );
        }
        if (cl == null) {
            cl = LogManager.class.getClassLoader();
        }

        return cl;
    }

    private static boolean validVersion(final String version) {
        for (final String v : COMPATIBLE_API_VERSIONS) {
            if (version.startsWith(v)) {
                return true;
            }
        }
        return false;
    }

}
