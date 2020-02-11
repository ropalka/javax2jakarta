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

/**
 * Utility class for working with class file content
 * in accordance with Java VM specification (version 13).
 *
 * <a href="mailto:ropalka@redhat.com">Richard Opálka</a>
 */
final class ClassFileUtils {

    static final byte UTF8 = 1;
    static final byte INTEGER = 3;
    static final byte FLOAT = 4;
    static final byte LONG = 5;
    static final byte DOUBLE = 6;
    static final byte CLASS = 7;
    static final byte STRING = 8;
    static final byte FIELD_REF = 9;
    static final byte METHOD_REF = 10;
    static final byte INTERFACE_METHOD_REF = 11;
    static final byte NAME_AND_TYPE = 12;
    static final byte METHOD_HANDLE = 15;
    static final byte METHOD_TYPE = 16;
    static final byte DYNAMIC = 17;
    static final byte INVOKE_DYNAMIC = 18;
    static final byte MODULE = 19;
    static final byte PACKAGE = 20;

    private ClassFileUtils() {
        // forbidden instantiation
    }

    static int readUnsignedShort(final byte[] clazz, final int offset) {
        return ((clazz[offset] & 0xFF) << 8) | (clazz[offset + 1] & 0xFF);
    }

    static void writeUnsignedShort(final byte[] clazz, final int offset, final int newValue) {
        clazz[offset] = (byte) (newValue >>> 8);
        clazz[offset + 1] = (byte) newValue;
    }

    static String readUTF8(final byte[] clazz, final int position, final int limit) {
        final char[] charBuffer = new char[limit - position];
        int charArrayLength = 0;
        int processedBytes = position;
        int currentByte;
        while (processedBytes < limit) {
            currentByte = clazz[processedBytes++];
            if ((currentByte & 0x80) == 0) {
                charBuffer[charArrayLength++] = (char) (currentByte & 0x7F);
            } else if ((currentByte & 0xE0) == 0xC0) {
                charBuffer[charArrayLength++] = (char) (((currentByte & 0x1F) << 6) + (clazz[position + processedBytes++] & 0x3F));
            } else {
                charBuffer[charArrayLength++] = (char) (((currentByte & 0xF) << 12) + ((clazz[position + processedBytes++] & 0x3F) << 6) + (clazz[position + processedBytes++] & 0x3F));
            }
        }
        return new String(charBuffer, 0, charArrayLength);
    }

    static byte[] writeUTF8(final String data) {
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

    /**
     * Counts how many <code>CONSTANT_Utf8_info</code> structures are present in class constant pool.
     *
     * @param clazz class bytes
     * @param offset start of class constant pool definition
     * @param poolSize class constant pool size
     * @return
     */
    static int countUtf8Items(final byte[] clazz, final int offset, final int poolSize) {
        int retVal = 0;
        int index = offset;
        int utf8Length;
        byte tag;

        for (int i = 1; i < poolSize; i++) {
            tag = clazz[index++];
            if (tag == UTF8) {
                retVal++;
                utf8Length = readUnsignedShort(clazz, index);
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

}
