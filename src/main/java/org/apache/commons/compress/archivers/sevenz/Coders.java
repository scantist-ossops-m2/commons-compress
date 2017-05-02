/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.commons.compress.archivers.sevenz;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.utils.FlushShieldFilterOutputStream;
import org.tukaani.xz.ARMOptions;
import org.tukaani.xz.ARMThumbOptions;
import org.tukaani.xz.FilterOptions;
import org.tukaani.xz.FinishableWrapperOutputStream;
import org.tukaani.xz.IA64Options;
import org.tukaani.xz.PowerPCOptions;
import org.tukaani.xz.SPARCOptions;
import org.tukaani.xz.X86Options;

class Coders {
    private static final Map<SevenZMethod, CoderBase> CODER_MAP = new HashMap<SevenZMethod, CoderBase>() {

        private static final long serialVersionUID = 1664829131806520867L;
    {
            put(SevenZMethod.COPY, new CopyDecoder());
            put(SevenZMethod.LZMA, new LZMADecoder());
            put(SevenZMethod.LZMA2, new LZMA2Decoder());
            put(SevenZMethod.DEFLATE, new DeflateDecoder());
            put(SevenZMethod.BZIP2, new BZIP2Decoder());
            put(SevenZMethod.AES256SHA256, new AES256SHA256Decoder());
            put(SevenZMethod.BCJ_X86_FILTER, new BCJDecoder(new X86Options()));
            put(SevenZMethod.BCJ_PPC_FILTER, new BCJDecoder(new PowerPCOptions()));
            put(SevenZMethod.BCJ_IA64_FILTER, new BCJDecoder(new IA64Options()));
            put(SevenZMethod.BCJ_ARM_FILTER, new BCJDecoder(new ARMOptions()));
            put(SevenZMethod.BCJ_ARM_THUMB_FILTER, new BCJDecoder(new ARMThumbOptions()));
            put(SevenZMethod.BCJ_SPARC_FILTER, new BCJDecoder(new SPARCOptions()));
            put(SevenZMethod.DELTA_FILTER, new DeltaDecoder());
        }};

    static CoderBase findByMethod(final SevenZMethod method) {
        return CODER_MAP.get(method);
    }

    static InputStream addDecoder(final String archiveName, final InputStream is, final long uncompressedLength,
            final Coder coder, final byte[] password) throws IOException {
        final CoderBase cb = findByMethod(SevenZMethod.byId(coder.decompressionMethodId));
        if (cb == null) {
            throw new IOException("Unsupported compression method " +
                                  Arrays.toString(coder.decompressionMethodId)
                                  + " used in " + archiveName);
        }
        return cb.decode(archiveName, is, uncompressedLength, coder, password);
    }
    
    static OutputStream addEncoder(final OutputStream out, final SevenZMethod method,
                                   final Object options) throws IOException {
        final CoderBase cb = findByMethod(method);
        if (cb == null) {
            throw new IOException("Unsupported compression method " + method);
        }
        return cb.encode(out, options);
    }

    static class CopyDecoder extends CoderBase {
        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength,
                final Coder coder, final byte[] password) throws IOException {
            return in; 
        }
        @Override
        OutputStream encode(final OutputStream out, final Object options) {
            return out;
        }
    }

    static class BCJDecoder extends CoderBase {
        private final FilterOptions opts;
        BCJDecoder(final FilterOptions opts) {
            this.opts = opts;
        }

        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength,
                final Coder coder, final byte[] password) throws IOException {
            try {
                return opts.getInputStream(in);
            } catch (final AssertionError e) {
                throw new IOException("BCJ filter used in " + archiveName
                                      + " needs XZ for Java > 1.4 - see "
                                      + "http://commons.apache.org/proper/commons-compress/limitations.html#7Z",
                                      e);
            }
        }
        
        @SuppressWarnings("resource")
        @Override
        OutputStream encode(final OutputStream out, final Object options) {
            return new FlushShieldFilterOutputStream(opts.getOutputStream(new FinishableWrapperOutputStream(out)));
        }
    }
    
    static class DeflateDecoder extends CoderBase {
        DeflateDecoder() {
            super(Number.class);
        }

        @SuppressWarnings("resource") // caller must close the InputStream
        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength,
                final Coder coder, final byte[] password)
            throws IOException {
            final Inflater inflater = new Inflater(true);
            final InflaterInputStream inflaterInputStream = new InflaterInputStream(new DummyByteAddingInputStream(in),
                    inflater);
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    return inflaterInputStream.read();
                }

                @Override
                public int read(final byte[] b, final int off, final int len) throws IOException {
                    return inflaterInputStream.read(b, off, len);
                }

                @Override
                public int read(final byte[] b) throws IOException {
                    return inflaterInputStream.read(b);
                }

                @Override
                public void close() throws IOException {
                    try {
                        inflaterInputStream.close();
                    } finally {
                        inflater.end();
                    }
                }
            };
        }
        @Override
        OutputStream encode(final OutputStream out, final Object options) {
            final int level = numberOptionOrDefault(options, 9);
            final Deflater deflater = new Deflater(level, true);
            final DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(out, deflater);
            return new OutputStream() {
                @Override
                public void write(final int b) throws IOException {
                    deflaterOutputStream.write(b);
                }

                @Override
                public void write(final byte[] b) throws IOException {
                    deflaterOutputStream.write(b);
                }

                @Override
                public void write(final byte[] b, final int off, final int len) throws IOException {
                    deflaterOutputStream.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    try {
                        deflaterOutputStream.close();
                    } finally {
                        deflater.end();
                    }
                }
            };
        }
    }

    static class BZIP2Decoder extends CoderBase {
        BZIP2Decoder() {
            super(Number.class);
        }

        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength,
                final Coder coder, final byte[] password)
                throws IOException {
            return new BZip2CompressorInputStream(in);
        }
        @Override
        OutputStream encode(final OutputStream out, final Object options)
                throws IOException {
            final int blockSize = numberOptionOrDefault(options, BZip2CompressorOutputStream.MAX_BLOCKSIZE);
            return new BZip2CompressorOutputStream(out, blockSize);
        }
    }

    /**
     * ZLIB requires an extra dummy byte.
     *
     * @see java.util.zip.Inflater#Inflater(boolean)
     * @see org.apache.commons.compress.archivers.zip.ZipFile.BoundedInputStream
     */
    private static class DummyByteAddingInputStream extends FilterInputStream {
        private boolean addDummyByte = true;

        private DummyByteAddingInputStream(final InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result == -1 && addDummyByte) {
                addDummyByte = false;
                result = 0;
            }
            return result;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            final int result = super.read(b, off, len);
            if (result == -1 && addDummyByte) {
                addDummyByte = false;
                b[off] = 0;
                return 1;
            }
            return result;
        }
    }
}