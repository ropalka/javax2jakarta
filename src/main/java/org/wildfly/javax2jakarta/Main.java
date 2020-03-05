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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Command line tool for transforming class files or jar files.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opálka</a>
 */
public final class Main {

    private static final String CLASS_FILE_EXT = ".class";
    private static final String JAR_FILE_EXT = ".jar";

    public static void main(final String... args) throws IOException {
        final String sourceFile = args.length == 2 ? getFileName(args[0]) : "";
        final String targetFile = args.length == 2 ? getFileName(args[1]) : "";
        if (sourceFile.endsWith(CLASS_FILE_EXT) && targetFile.endsWith(CLASS_FILE_EXT)) {
            transformClassFile(sourceFile, targetFile);
        } else if (sourceFile.endsWith(JAR_FILE_EXT) && targetFile.endsWith(JAR_FILE_EXT)) {
            transformJarFile(sourceFile, targetFile);
        } else {
            printUsage();
        }
    }

    private static void transformClassFile(final String inClassFile, final String outClassFile) throws IOException {
        final Transformer t = getTransformer();
        // get original class content
        final ByteArrayOutputStream targetBAOS = new ByteArrayOutputStream();
        final Path source = Paths.get(inClassFile);
        Files.copy(source, targetBAOS);
        final byte[] sourceBytes = targetBAOS.toByteArray();
        // transform class
        final byte[] targetBytes = t.transform(sourceBytes);
        // write modified class content
        final ByteArrayInputStream sourceBAIS = new ByteArrayInputStream(targetBytes);
        final Path target = Paths.get(outClassFile);
        Files.copy(sourceBAIS, target);
    }

    private static void transformJarFile(final String inJarFile, final String outJarFile) throws IOException {
        throw new UnsupportedOperationException();
    }

    private static Transformer getTransformer() throws IOException {
        final Properties defaultMapping = new Properties();
        defaultMapping.load(Transformer.class.getResourceAsStream("/default.mapping"));
        String to;
        Transformer.Builder builder = Transformer.newInstance();
        for (String from : defaultMapping.stringPropertyNames()) {
            to = defaultMapping.getProperty(from);
            builder.addMapping(from, to);
        }
        return builder.build();
    }

    private static String getFileName(final String arg) {
        if (arg == null || "".equals(arg)) return "";
        return arg.replace("\\", "/").replaceAll("//", "/");
    }

    private static void printUsage() {
        System.out.println("Usage: " + Main.class.getName() + " source.class target.class");
        System.out.println("       (to transform a class)");
        System.out.println("   or  " + Main.class.getName() + " source.jar target.jar");
        System.out.println("       (to transform a jar file)");
        System.exit(1);
    }

}
