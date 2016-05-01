/*
 * Copyright (c) 2013, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.stdlib;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.source.LineLocation;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.RubyContext;

import java.io.PrintStream;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

public class CoverageManager {

    public class LineTag {
    }

    public static final long NO_CODE = -1;

    private final Instrumenter instrumenter;

    private boolean enabled = false;

    private final Map<Source, AtomicLongArray> counters = new ConcurrentHashMap<>();
    private final Map<Source, BitSet> codeMap = new HashMap<>();

    public CoverageManager(RubyContext context, Instrumenter instrumenter) {
        this.instrumenter = instrumenter;

        if (context.getOptions().COVERAGE_GLOBAL) {
            enable();
        }
    }

    public synchronized void setLineHasCode(LineLocation line) {
        BitSet bitmap = codeMap.get(line.getSource());

        if (bitmap == null) {
            bitmap = new BitSet(line.getSource().getLineCount());
            codeMap.put(line.getSource(), bitmap);
        }

        bitmap.set(line.getLineNumber() - 1);
    }

    @CompilerDirectives.TruffleBoundary
    public void enable() {
        if (enabled) {
            throw new UnsupportedOperationException();
        }

        instrumenter.attachFactory(SourceSectionFilter.newBuilder().tagIs(LineTag.class).build(), new ExecutionEventNodeFactory() {

            @Override
            public ExecutionEventNode create(EventContext eventContext) {
                return new ExecutionEventNode() {

                    @CompilationFinal private AtomicLongArray counters;
                    @CompilationFinal private int lineNumber;

                    @Override
                    protected void onEnter(VirtualFrame frame) {
                        if (counters == null) {
                            CompilerDirectives.transferToInterpreterAndInvalidate();
                            final SourceSection sourceSection = getEncapsulatingSourceSection();
                            counters = getCounters(sourceSection.getSource());
                            lineNumber = sourceSection.getStartLine() - 1;
                        }

                        counters.incrementAndGet(lineNumber);
                    }

                };
            }

        });

        enabled = true;
    }

    private synchronized AtomicLongArray getCounters(Source source) {
        AtomicLongArray c = counters.get(source);

        if (c == null) {
            c = new AtomicLongArray(source.getLineCount());
            counters.put(source, c);
        }

        return c;
    }

    public Map<Source, long[]> getCounts() {
        final Map<Source, long[]> counts = new HashMap<>();

        for (Map.Entry<Source, AtomicLongArray> entry : counters.entrySet()) {
            final BitSet hasCode = codeMap.get(entry.getKey());

            final long[] array = new long[entry.getValue().length()];

            for (int n = 0; n < array.length; n++) {
                if (hasCode != null && hasCode.get(n)) {
                    array[n] = entry.getValue().get(n);
                } else {
                    array[n] = NO_CODE;
                }
            }

            counts.put(entry.getKey(), array);
        }

        return counts;
    }

    public void print(PrintStream out) {
        for (Map.Entry<Source, AtomicLongArray> entry : counters.entrySet()) {
            final BitSet hasCode = codeMap.get(entry.getKey());

            out.println(entry.getKey().getName());

            for (int n = 0; n < entry.getValue().length(); n++) {
                String line = entry.getKey().getCode(n + 1);

                if (line.length() > 60) {
                    line = line.substring(0, 60);
                }

                out.print("  ");

                if (hasCode != null && hasCode.get(n)) {
                    out.printf("% 12d", entry.getValue().get(n));
                } else {
                    out.print("           -");
                }

                out.printf("  %s%n", line);
            }
        }
    }

}
