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

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Thread.currentThread;

/**
 // TODO: javadoc
 * <a href="mailto:ropalka@redhat.com">Richard Opálka</a>
 */
public final class Transformer {

    private static final byte UTF8 = 1;
    private static final byte INTEGER = 3;
    private static final byte FLOAT = 4;
    private static final byte LONG = 5;
    private static final byte DOUBLE = 6;
    private static final byte CLASS = 7;
    private static final byte STRING = 8;
    private static final byte FIELD_REF = 9;
    private static final byte METHOD_REF = 10;
    private static final byte INTERFACE_METHOD_REF = 11;
    private static final byte NAME_AND_TYPE = 12;
    private static final byte METHOD_HANDLE = 15;
    private static final byte METHOD_TYPE = 16;
    private static final byte DYNAMIC = 17;
    private static final byte INVOKE_DYNAMIC = 18;
    private static final byte MODULE = 19;
    private static final byte PACKAGE = 20;

    private final byte[][] mappingFrom;
    private final byte[][] mappingTo;
    private final int minimum;

    private Transformer(final byte[][] mappingFrom, final byte[][] mappingTo, final int minimum) {
        this.mappingFrom = mappingFrom;
        this.mappingTo = mappingTo;
        this.minimum = minimum;
    }

    public byte[] transform(final byte[] classBuffer) {
        int position = 8; // skip magic
        final int poolSize = readUnsignedShort(classBuffer, position);
        position = 10;
        final int utf8ItemsCount = countUtf8Items(classBuffer, poolSize, position);
        byte tag;
        int byteArrayLength;
        String s;
        boolean transformClass;
        for (int i = 1; i < poolSize; i++) {
            tag = classBuffer[position++];
            if (tag == UTF8) {
                byteArrayLength = readUnsignedShort(classBuffer, position);
                position += 2;
                s = readUTF8(classBuffer, byteArrayLength, position);
                position += byteArrayLength;
                // TODO: it is possible to detect exact byte[] array value for transformed class
                // TODO: algorithm: for every mapping compute difference
                //                  process whole contant pool and at the end you will have computed value
                // TODO: it is not necessary to transform UTF8 to string above - translate mapping to JVM modified UTF8 instead
                // TODO: implement javax -> jakarta transformation
            } else if (tag == CLASS || tag == STRING || tag == METHOD_TYPE || tag == MODULE || tag == PACKAGE) {
                position += 2;
            } else if (tag == LONG || tag == DOUBLE) {
                position += 8;
                i++; // see JVM specification
            } else if (tag == INTEGER || tag == FLOAT || tag == FIELD_REF || tag == METHOD_REF
            || tag == INTERFACE_METHOD_REF || tag == NAME_AND_TYPE
            || tag == DYNAMIC || tag == INVOKE_DYNAMIC) {
                position += 4;
            } else if (tag == METHOD_HANDLE) {
                position += 3;
            } else {
                throw new UnsupportedClassVersionError();
            }
        }
        throw new UnsupportedOperationException(); // TODO: implement
    }

    private int countUtf8Items(final byte[] classBytes, final int constantPoolSize, final int position) {
        int retVal = 0;
        int index = position;
        int utf8Length;
        byte tag;

        for (int i = 1; i < constantPoolSize; i++) {
            tag = classBytes[index++];
            if (tag == UTF8) {
                retVal++;
                utf8Length = readUnsignedShort(classBytes, index);
                index += (utf8Length + 2);
            } else if (tag == CLASS || tag == STRING || tag == METHOD_TYPE || tag == MODULE || tag == PACKAGE) {
                index += 2;
            } else if (tag == METHOD_HANDLE) {
                index += 3;
            } else if (tag == LONG || tag == DOUBLE) {
                index += 8;
                i++;
            } else if (tag == INTEGER || tag == FLOAT || tag == FIELD_REF || tag == METHOD_REF ||
                       tag == INTERFACE_METHOD_REF || tag == NAME_AND_TYPE || tag == DYNAMIC || tag == INVOKE_DYNAMIC) {
                index += 4;
            } else {
                throw new UnsupportedClassVersionError();
            }
        }

        return retVal;
    }

    private int[] findReplacementsInString(final byte[] clazzData, final int position, final int limit) {
        int[] retVal = null;
        int mappingIndex;
        int replacementCount = 0;
        int diffInBytes = 0;
        for (int i = position; i < limit; i++) {
            for (int j = 1; j < mappingFrom.length; j++) {
                if (limit - i < mappingFrom[j].length) continue;
                mappingIndex = j;
                for (int k = 0; k < mappingFrom[j].length; k++) {
                    if (clazzData[i + k] != mappingFrom[j][k]) {
                        mappingIndex = 0;
                        break;
                    }
                }
                if (mappingIndex != 0) {
                    if (retVal == null) {
                        retVal = new int[((limit - position) / minimum) + 2];
                        retVal[0] = position;
                    }
                    retVal[2 + replacementCount++] = mappingIndex << 16 | i - position;
                    diffInBytes += mappingTo[mappingIndex].length - mappingFrom[mappingIndex].length;
                    i += mappingFrom[j].length;
                }
            }
        }
        if (retVal != null) {
            retVal[1] = diffInBytes;
        }
        return retVal;
    }

    private int readUnsignedShort(final byte[] classBuffer, final int position) {
        return ((classBuffer[position] & 0xFF) << 8) | (classBuffer[position + 1] & 0xFF);
    }

    private static String readUTF8(final byte[] classBuffer, final int byteArrayLength, final int position) {
        final char[] charBuffer = new char[byteArrayLength];
        int charArrayLength = 0;
        int processedBytes = 0;
        int currentByte;
        while (processedBytes < byteArrayLength) {
            currentByte = classBuffer[processedBytes++];
            if ((currentByte & 0x80) == 0) {
                charBuffer[charArrayLength++] = (char) (currentByte & 0x7F);
            } else if ((currentByte & 0xE0) == 0xC0) {
                charBuffer[charArrayLength++] = (char) (((currentByte & 0x1F) << 6) + (classBuffer[position + processedBytes++] & 0x3F));
            } else {
                charBuffer[charArrayLength++] = (char) (((currentByte & 0xF) << 12) + ((classBuffer[position + processedBytes++] & 0x3F) << 6) + (classBuffer[position + processedBytes++] & 0x3F));
            }
        }
        return new String(charBuffer, 0, charArrayLength);
    }

    private static byte[] writeUTF8(final String data) {
        final byte[] retVal = new byte[getByteArraySize(data)];
        int bytesCount = 0;
        int currentChar;

        for (int i = 0; i < data.length(); i++) {
            currentChar = data.charAt(i);
            if (currentChar < 0x80 && currentChar != 0) {
                retVal[bytesCount++] = (byte) currentChar;
            } else if (currentChar >= 0x800) {
                retVal[bytesCount++] = (byte) (0xE0 | ((currentChar >> 12) & 0x0F));
                retVal[bytesCount++] = (byte) (0x80 | ((currentChar >> 6) & 0x3F));
                retVal[bytesCount++] = (byte) (0x80 | ((currentChar >> 0) & 0x3F));
            } else {
                retVal[bytesCount++] = (byte) (0xC0 | ((currentChar >> 6) & 0x1F));
                retVal[bytesCount++] = (byte) (0x80 | ((currentChar >> 0) & 0x3F));
            }
        }

        return retVal;
    }

    private static int getByteArraySize(final String data) {
        int retVal = data.length();
        int currentChar;

        for (int i = 0; i < data.length(); i++) {
            currentChar = data.charAt(i);
            if (currentChar >= 0x80 || currentChar == 0) {
                retVal += (currentChar >= 0x800) ? 2 : 1;
            }
        }

        return retVal;
    }

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

        public void addMapping(final String from, final String to) {
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
                mappingFrom[i] = writeUTF8(mappingEntry.getKey());
                mappingTo[i] = writeUTF8(mappingEntry.getValue());
                if (minimum > mappingFrom[i].length) {
                    minimum = mappingFrom[i].length;
                }
                i++;
            }
            return new Transformer(mappingFrom, mappingTo, minimum);
        }
    }

}
