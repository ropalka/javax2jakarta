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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
        final File sourceFile = args.length == 2 ? getFile(args[0]) : null;
        final File targetFile = args.length == 2 ? getFile(args[1]) : null;
        if (sourceFile != null && targetFile != null) {
            if (sourceFile.exists() && sourceFile.isFile()) {
                if (sourceFile.getName().endsWith(CLASS_FILE_EXT) && targetFile.getName().endsWith(CLASS_FILE_EXT)) {
                    transformClassFile(sourceFile, targetFile);
                    return;
                } else if (sourceFile.getName().endsWith(JAR_FILE_EXT) && targetFile.getName().endsWith(JAR_FILE_EXT)) {
                    transformJarFile(sourceFile, targetFile);
                    return;
                }
            }
        }
        printUsage();
    }

    private static void transformClassFile(final File inClassFile, final File outClassFile) throws IOException {
        if (inClassFile.length() > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException("File " + inClassFile.getAbsolutePath() + " too big! Maximum allowed file size is " + Integer.MAX_VALUE + " bytes");
        }

        final Transformer t = getTransformer();
        byte[] clazz = new byte[(int)inClassFile.length()];
        readClassBytes(inClassFile, clazz);
        clazz = t.transform(clazz);
        writeClassBytes(outClassFile, clazz);
    }

    private static void readClassBytes(final File file, final byte[] clazz) throws IOException {
        try (final FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < clazz.length) {
                offset += fis.read(clazz, offset, clazz.length - offset);
            }
        }
    }

    private static void writeClassBytes(final File file, final byte[] clazz) throws IOException {
        try (final FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(clazz);
        }
    }

    private static void transformJarFile(final File inJarFile, final File outJarFile) throws IOException {
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

    private static File getFile(final String arg) {
        return arg == null || "".equals(arg) ? null : new File(arg);
    }

    private static void printUsage() {
        System.out.println("Usage: " + Main.class.getName() + " source.class target.class");
        System.out.println("       (to transform a class)");
        System.out.println("   or  " + Main.class.getName() + " source.jar target.jar");
        System.out.println("       (to transform a jar file)");
        System.exit(1);
    }

}
