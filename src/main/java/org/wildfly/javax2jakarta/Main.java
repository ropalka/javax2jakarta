/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.javax2jakarta;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/**
 * Command line tool for transforming class files or jar files.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opálka</a>
 */
public final class Main {

    private static final String CLASS_FILE_EXT = ".class";
    private static final String JAR_FILE_EXT = ".jar";

    public static void main(final String... args) throws IOException {
        if (!validParameters(args)) {
            printUsage();
            System.exit(1);
        }

        final File sourceFile = new File(args[0]);
        final File targetFile = new File(args[1]);
        if (sourceFile.getName().endsWith(CLASS_FILE_EXT)) {
            transformClassFile(sourceFile, targetFile);
        } else if (sourceFile.getName().endsWith(JAR_FILE_EXT)) {
            transformJarFile(sourceFile, targetFile);
        }
    }

    private static boolean validParameters(final String... args) {
        if (args.length != 2) {
            System.err.println("2 arguments required");
            return false;
        }
        if (args[0] == null || args[1] == null) {
            System.err.println("Argument cannot be null");
            return false;
        }
        if ("".equals(args[0]) || "".equals(args[1])) {
            System.err.println("Argument cannot be empty string");
            return false;
        }
        final File sourceFile = new File(args[0]);
        if (!sourceFile.getName().endsWith(CLASS_FILE_EXT) && !sourceFile.getName().endsWith(JAR_FILE_EXT)) {
            System.err.println("Supported file extensions are " + CLASS_FILE_EXT + " or " + JAR_FILE_EXT + " : " + sourceFile.getAbsolutePath());
            return false;
        }
        if (!sourceFile.exists()) {
            System.err.println("Couldn't find file " + sourceFile.getAbsolutePath());
            return false;
        }
        final File targetFile = new File(args[1]);
        if (targetFile.exists()) {
            System.err.println("Delete file or directory " + targetFile.getAbsolutePath());
            return false;
        }
        return true;
    }

    private static void transformClassFile(final File inClassFile, final File outClassFile) throws IOException {
        if (inClassFile.length() > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException("File " + inClassFile.getAbsolutePath() + " too big! Maximum allowed file size is " + Integer.MAX_VALUE + " bytes");
        }

        final Transformer t = getTransformer();
        byte[] clazz = new byte[(int)inClassFile.length()];
        readBytes(new FileInputStream(inClassFile), clazz, true);
        clazz = t.transform(clazz);
        writeBytes(new FileOutputStream(outClassFile), clazz, true);
    }

    private static void safeClose(final Closeable c) {
        try {
            if (c != null) c.close();
        } catch (final Throwable t) {
            // ignored
        }
    }

    private static void readBytes(final InputStream is, final byte[] clazz, final boolean closeStream) throws IOException {
        try {
            int offset = 0;
            while (offset < clazz.length) {
                offset += is.read(clazz, offset, clazz.length - offset);
            }
        } finally {
            if (closeStream) {
                safeClose(is);
            }
        }
    }

    private static void writeBytes(final OutputStream os, final byte[] clazz, final boolean closeStream) throws IOException {
        try {
            os.write(clazz);
        } finally {
            if (closeStream) {
                safeClose(os);
            }
        }
    }

    private static void transformJarFile(final File inJarFile, final File outJarFile) throws IOException {
        final Transformer t = getTransformer();
        final Calendar calendar = Calendar.getInstance();
        JarFile jar = null;
        JarOutputStream jarOutputStream = null;
        JarEntry inJarEntry, outJarEntry;
        byte[] buffer;

        try {
            jar = new JarFile(inJarFile);
            jarOutputStream = new JarOutputStream(new FileOutputStream(outJarFile));

            for (final Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                // jar file entry preconditions
                inJarEntry = e.nextElement();
                if (inJarEntry.getSize() == 0) {
                    continue; // directories
                }
                if (inJarEntry.getSize() < 0) {
                    throw new UnsupportedOperationException("File size " + inJarEntry.getName() + " unknown! File size must be positive number");
                }
                if (inJarEntry.getSize() > Integer.MAX_VALUE) {
                    throw new UnsupportedOperationException("File " + inJarEntry.getName() + " too big! Maximum allowed file size is " + Integer.MAX_VALUE + " bytes");
                }
                // reading original jar file entry
                buffer = new byte[(int) inJarEntry.getSize()];
                readBytes(jar.getInputStream(inJarEntry), buffer, true);
                // transform byte code of class files
                if (inJarEntry.getName().endsWith(CLASS_FILE_EXT)) {
                    buffer = t.transform(buffer);
                }
                // writing modified jar file entry
                outJarEntry = new JarEntry(inJarEntry.getName());
                outJarEntry.setSize(buffer.length);
                outJarEntry.setTime(calendar.getTimeInMillis());
                jarOutputStream.putNextEntry(outJarEntry);
                writeBytes(jarOutputStream, buffer, false);
                jarOutputStream.closeEntry();
            }
        } finally {
            safeClose(jar);
            safeClose(jarOutputStream);
        }
    }

    private static Transformer getTransformer() throws IOException {
        InputStream is = null;
        try {
            is = Transformer.class.getResourceAsStream("/default.mapping");
            final Properties defaultMapping = new Properties();
            defaultMapping.load(is);
            String to;
            final Transformer.Builder builder = Transformer.newInstance();
            for (String from : defaultMapping.stringPropertyNames()) {
                to = defaultMapping.getProperty(from);
                builder.addMapping(from, to);
            }
            return builder.build();
        } finally {
            safeClose(is);
        }
    }

    private static void printUsage() {
        System.err.println();
        System.err.println("Usage: " + Main.class.getName() + " source.class target.class");
        System.err.println("       (to transform a class)");
        System.err.println("   or  " + Main.class.getName() + " source.jar target.jar");
        System.err.println("       (to transform a jar file)");
        System.err.println("");
        System.err.println("Notes:");
        System.err.println(" * source.class or source.jar must exist");
        System.err.println(" * target.class or target.jar cannot exist");
    }

}
