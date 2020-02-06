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

import java.nio.ByteBuffer;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, String> mapping;

    private Transformer(final Map<String, String> mapping) {
        this.mapping = mapping;
    }

    public byte[] transform(final byte[] clazz) {
        final ByteBuffer classBB = ByteBuffer.wrap(clazz);
        classBB.position(8);
        final int poolSize = classBB.getShort();
        byte tag;
        for (int i = 1; i < poolSize; i++) {
            tag = classBB.get();
            if (tag == UTF8) {
                // TODO: implement javax -> jakarta transformation
            } else if (tag == CLASS || tag == STRING || tag == METHOD_TYPE || tag == MODULE || tag == PACKAGE) {
                classBB.position(classBB.position() + 2);
            } else if (tag == LONG || tag == DOUBLE) {
                classBB.position(classBB.position() + 8);
                i++; // see JVM specification
            } else if (tag == INTEGER || tag == FLOAT || tag == FIELD_REF || tag == METHOD_REF
            || tag == INTERFACE_METHOD_REF || tag == NAME_AND_TYPE
            || tag == DYNAMIC || tag == INVOKE_DYNAMIC) {
                classBB.position(classBB.position() + 4);
            } else if (tag == METHOD_HANDLE) {
                classBB.position(classBB.position() + 3);
            } else {
                throw new UnsupportedClassVersionError();
            }
        }
        throw new UnsupportedOperationException(); // TODO: implement
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
            mapping = new ConcurrentHashMap<>();
        }

        public void addMapping(final String from, final String to) {
            // preconditions
            if (thread != currentThread()) throw new ConcurrentModificationException();
            if (built) throw new IllegalStateException();
            // implementation
            for (String key : mapping.keySet()) {
                if (key.contains(from)) return;
            }
            mapping.put(from, to);
        }

        public Transformer build() {
            // preconditions
            if (thread != currentThread()) throw new ConcurrentModificationException();
            if (built) throw new IllegalStateException();
            // implementation
            built = true;
            return new Transformer(mapping);
        }
    }
}
