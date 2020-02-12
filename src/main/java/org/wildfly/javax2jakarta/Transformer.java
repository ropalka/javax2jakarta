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

import static java.lang.Thread.currentThread;
import static org.wildfly.javax2jakarta.ClassFileUtils.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Simple thread safe class file transformer.
 *
 * <a href="mailto:ropalka@redhat.com">Richard Opálka</a>
 */
public final class Transformer {

    /**
     * Represents strings we are searching for in <code>CONSTANT_Utf8_info</code> structures (encoded in modified UTF-8).
     * Mapping on index <code>zero</code> is undefined. Mappings are defined from index <code>one</code>.
     */
    private final byte[][] mappingFrom;

    /**
     * Represents strings we will replace matches with inside <code>CONSTANT_Utf8_info</code> structures (encoded in modified UTF-8).
     * Mapping on index <code>zero</code> is undefined. Mappings are defined from index <code>one</code>.
     */
    private final byte[][] mappingTo;

    /**
     * Used for detecting maximum size of internal patch info arrays and for decreasing patch search space.
     */
    private final int minimum;

    /**
     * Constructor.
     *
     * @param mappingFrom modified UTF-8 encoded search strings
     * @param mappingTo modified UTF-8 encoded patch strings
     * @param minimum length of the smallest search string
     */
    private Transformer(final byte[][] mappingFrom, final byte[][] mappingTo, final int minimum) {
        this.mappingFrom = mappingFrom;
        this.mappingTo = mappingTo;
        this.minimum = minimum;
    }

    public byte[] transform(final byte[] classBytes) {
        final int poolSize = readUnsignedShort(classBytes, POOL_SIZE_INDEX);
        int position = POOL_CONTENT_INDEX;
        final int utf8ItemsCount = countUtf8Items(classBytes);
        List<int[]> patches = null;
        byte tag;
        int byteArrayLength;
        int diffInBytes = 0;
        int[] patchInfo;
        for (int i = 1; i < poolSize; i++) {
            tag = classBytes[position++];
            if (tag == UTF8) {
                byteArrayLength = readUnsignedShort(classBytes, position);
                position += 2;
                patchInfo = getPatch(classBytes, position, position + byteArrayLength);
                if (patchInfo != null) {
                    if (patches == null) {
                        patches = new ArrayList<>(utf8ItemsCount);
                    }
                    diffInBytes += patchInfo[1];
                    patches.add(patchInfo);
                }
                position += byteArrayLength;
            } else if (tag == CLASS || tag == STRING || tag == METHOD_TYPE || tag == MODULE || tag == PACKAGE) {
                position += 2;
            } else if (tag == LONG || tag == DOUBLE) {
                position += 8;
                i++; // see JVM specification
            } else if (tag == INTEGER || tag == FLOAT || tag == FIELD_REF || tag == METHOD_REF ||
                       tag == INTERFACE_METHOD_REF || tag == NAME_AND_TYPE || tag == DYNAMIC || tag == INVOKE_DYNAMIC) {
                position += 4;
            } else if (tag == METHOD_HANDLE) {
                position += 3;
            } else {
                throw new UnsupportedClassVersionError();
            }
        }

        if (patches == null) return classBytes;

        final byte[] retVal = new byte[classBytes.length + diffInBytes];
        System.arraycopy(classBytes, 0, retVal, 0, 10); // magic, versions, constant pool size
        final Iterator<int[]> it = patches.iterator();
        int[] replacements;
        int oldClassBytePosition = 10, newClassBytePosition = 10;
        int replacementIndex, replacementPosition, diff, copyLength;

        while (it.hasNext()) {
            replacements = it.next();
            if (replacements == null) {
                break;
            } else {
                // copy all till start of next utf8 item
                copyLength = replacements[0] - oldClassBytePosition;
                System.arraycopy(classBytes, oldClassBytePosition, retVal, newClassBytePosition, copyLength);
                oldClassBytePosition += copyLength;
                newClassBytePosition += copyLength;
                // patch utf8 length
                diff = readUnsignedShort(classBytes, oldClassBytePosition - 2);
                diff += replacements[1];
                writeUnsignedShort(retVal, newClassBytePosition - 2, diff);
                // apply replacements
                for (int i = 2; i < replacements.length; i++) {
                    replacementIndex = replacements[i] >> 16;
                    if (replacementIndex == 0) break;
                    replacementPosition = replacements[i] & 0xFF;
                    // copy till begin of patch
                    copyLength = replacementPosition - oldClassBytePosition;
                    System.arraycopy(classBytes, oldClassBytePosition, retVal, newClassBytePosition, copyLength);
                    oldClassBytePosition += copyLength;
                    newClassBytePosition += copyLength;
                    // real patch
                    System.arraycopy(mappingTo[replacementIndex], 0, retVal, newClassBytePosition, mappingTo[replacementIndex].length);
                    oldClassBytePosition += mappingFrom[replacementIndex].length;
                    newClassBytePosition += mappingTo[replacementIndex].length;
                }
            }
        }
        System.arraycopy(classBytes, oldClassBytePosition, retVal, newClassBytePosition, classBytes.length - oldClassBytePosition);
        return retVal;
    }

    /**
     * Returns <code>patch info</code> if patches were detected or <code>null</code> if there is no patch applicable.
     * Every <code>patch info</code> has the following format:
     * <p>
     *     <pre>
     *         +-----------+
     *         | integer 0 | byte position of beginning of <code>CONSTANT_Utf8_info</code> structure in original class file
     *         +-----------+
     *         | integer 1 | <code>CONSTANT_Utf8_info</code> structure difference in bytes after applied patches
     *         +-----------+
     *         | integer 2 | first two bytes hold non-zero mapping index in mapping tables of 1-st applied patch
     *         |           | last two bytes hold index of 1-st patch start inside original <code>CONSTANT_Utf8_info</code> structure
     *         +-----------+
     *         | integer 2 | first two bytes hold non-zero mapping index in mapping tables of 2-nd applied patch
     *         |           | last two bytes hold index of 2-nd patch start inside original <code>CONSTANT_Utf8_info</code> structure
     *         +-----------+
     *         |    ...    | etc
     *         +-----------+
     *         | integer N | first two bytes of mapping index equal to zero indicate <code>patch info</code> structure end
     *         +-----------+
     *     </pre>
     * </p>
     *
     * @param clazz class byte code
     * @param offset beginning index of <code>CONSTANT_Utf8_info</code> structure being investigated
     * @param limit first index not belonging to investigated <code>CONSTANT_Utf8_info</code> structure
     * @return
     */
    private int[] getPatch(final byte[] clazz, final int offset, final int limit) {
        int[] retVal = null;
        int mappingIndex;
        int patchIndex = 2;

        for (int i = offset; i <= limit - minimum; i++) {
            for (int j = 1; j < mappingFrom.length; j++) {
                if (limit - i < mappingFrom[j].length) continue;
                mappingIndex = j;
                for (int k = 0; k < mappingFrom[j].length; k++) {
                    if (clazz[i + k] != mappingFrom[j][k]) {
                        mappingIndex = 0;
                        break;
                    }
                }
                if (mappingIndex != 0) {
                    if (retVal == null) {
                        retVal = new int[((limit - i) / minimum) + 2];
                        retVal[0] = offset;
                    }
                    retVal[patchIndex++] = mappingIndex << 16 | i;
                    retVal[1] += mappingTo[mappingIndex].length - mappingFrom[mappingIndex].length;
                    i += mappingFrom[j].length - 1;
                    break;
                }
            }
        }

        return retVal;
    }

    /**
     * Returns new builder for configuring the class file transformer.
     *
     * @return class file transformer builder
     */
    public static Transformer.Builder newInstance() {
        return new Builder();
    }

    public static final class Builder {
        private final Thread thread;
        private final Map<String, String> mapping;
        private boolean built;

        private Builder() {
            thread = currentThread();
            mapping = new HashMap<>();
        }

        public Builder addMapping(final String from, final String to) {
            // preconditions
            if (thread != currentThread()) throw new ConcurrentModificationException();
            if (built) throw new IllegalStateException();
            if (from == null || to == null) throw new IllegalArgumentException();
            if (from.length() == 0 || to.length() == 0) throw new IllegalArgumentException();
            // implementation
            for (String key : mapping.keySet()) {
                if (key.contains(from) || from.contains(key)) throw new IllegalArgumentException();
            }
            mapping.put(from, to);
            return this;
        }

        public Transformer build() {
            // preconditions
            if (thread != currentThread()) throw new ConcurrentModificationException();
            if (built) throw new IllegalStateException();
            if (mapping.size() == 0) throw new IllegalStateException();
            // implementation
            built = true;
            final int mappingSize = mapping.size() + 1;
            final byte[][] mappingFrom = new byte[mappingSize][];
            final byte[][] mappingTo = new byte[mappingSize][];
            int i = 1;
            int minimum = Integer.MAX_VALUE;
            for (Map.Entry<String, String> mappingEntry : mapping.entrySet()) {
                mappingFrom[i] = stringToUtf8(mappingEntry.getKey());
                mappingTo[i] = stringToUtf8(mappingEntry.getValue());
                if (minimum > mappingFrom[i].length) {
                    minimum = mappingFrom[i].length;
                }
                i++;
            }
            return new Transformer(mappingFrom, mappingTo, minimum);
        }
    }

    public static void main(final String... args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: " + Transformer.class + " sourceClassFile targetClassFile");
            return;
        }
        // configure transformer
        final Transformer t = Transformer.newInstance().addMapping("javax/", "jakarta/").build();
        // get original class content
        final ByteArrayOutputStream targetBAOS = new ByteArrayOutputStream();
        final Path source = Paths.get(args[0]);
        Files.copy(source, targetBAOS);
        final byte[] sourceBytes = targetBAOS.toByteArray();
        // transform class
        final byte[] targetBytes = t.transform(sourceBytes);
        // write modified class content
        final ByteArrayInputStream sourceBAIS = new ByteArrayInputStream(targetBytes);
        final Path target = Paths.get(args[1]);
        Files.copy(sourceBAIS, target);
    }

}
