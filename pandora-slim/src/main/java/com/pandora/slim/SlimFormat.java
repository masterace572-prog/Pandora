package com.pandora.slim;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Slim embed container (format "PSL1").
 *
 * <p>The classic Pandora design embeds a full copy of the original APK inside the
 * patched APK (assets/base.apk), which roughly doubles the output size. The slim
 * container instead stores only the parts of the original APK that cannot be
 * recovered from the patched outer APK at runtime:</p>
 *
 * <ul>
 *   <li>Entries the outer APK drops (binary manifest, META-INF signing metadata)
 *       are embedded raw.</li>
 *   <li>Entries stored DEFLATED in the original are embedded raw, because the
 *       re-compressed bytes in the outer APK are not bit-identical.</li>
 *   <li>Entries stored uncompressed (STORED) in the original — typically all the
 *       large native libraries — are NOT embedded. Their payload is recovered
 *       byte-exactly at runtime by inflating the outer APK copy, and verified by
 *       size and CRC-32.</li>
 *   <li>The APK Signing Block, central directory and trailing ZIP structures are
 *       embedded raw so the rebuilt file is byte-identical to the original.</li>
 * </ul>
 *
 * <p>For apps with extractNativeLibs=false (usually the large ones), this removes
 * almost all of the duplicated payload. All integers in the container are
 * big-endian. The builder and the payload runtime share this class so patch-time
 * verification exercises the exact same rebuild logic.</p>
 */
public final class SlimFormat {
    /** Location of the slim container inside a patched APK. */
    public static final String ContainerPath = "assets/pandora/slim.apk";
    /** Legacy full-copy location, still recognized by the runtime. */
    public static final String LegacyPath = "assets/base.apk";

    private static final int MAGIC = 0x50534C31; // "PSL1"
    private static final int VERSION = 1;

    private static final long LFH_SIG = 0x04034b50L;
    private static final long CDE_SIG = 0x02014b50L;
    private static final long EOCD_SIG = 0x06054b50L;
    private static final long ZIP64_EOCD_SIG = 0x06064b50L;
    private static final long ZIP64_LOC_SIG = 0x07064b50L;

    private static final int FLAG_ENCRYPTED = 0x0001;
    private static final int FLAG_DESCRIPTOR = 0x0008;
    private static final int FLAG_UTF8 = 0x0800;

    private static final int METHOD_STORED = 0;

    private SlimFormat() {
    }

    /** Thrown when an APK layout cannot be represented by the slim format. */
    public static final class UnsupportedLayoutException extends IOException {
        public UnsupportedLayoutException(String Message) {
            super(Message);
        }
    }

    private static final class Cde {
        String Name;
        byte[] NameBytes;
        int Flags;
        int Method;
        long Crc;
        long CompressedSize;
        long Size;
        long LocalOffset;
    }

    /** One reassembly step, in original file order. */
    private static final class Plan {
        Cde Entry;
        boolean Raw;
        long Offset;    // of Prefix/Span/Suffix inside the original file
        long PrefixEnd; // OUTER: end of the local header record
        long PayloadEnd;
        long End;       // end of this entry's span (start of the next record)
    }

    /**
     * Builds a slim container for {@code Original} and writes it to
     * {@code Destination}. {@code IsDropped} names the entries that will not
     * exist in the patched outer APK. Throws {@link UnsupportedLayoutException}
     * when the input cannot be slimmed safely; callers fall back to a full copy.
     */
    public static void write(File Original, Predicate<String> IsDropped,
                             File Destination) throws IOException {
        final long TotalLength = Original.length();

        try (RandomAccessFile Input = new RandomAccessFile(Original, "r")) {
            long TailStart = FindEocd(Input);
            ByteBuffer Eocd = Slice(Input, TailStart, TotalLength - TailStart);
            if (U16(Eocd, 4) != 0 || U16(Eocd, 6) != 0) {
                throw new UnsupportedLayoutException("multi-disk ZIP");
            }
            long CdSize = U32(Eocd, 12);
            long CdOffset = U32(Eocd, 16);
            int CountTotal = U16(Eocd, 10);
            if (U16(Eocd, 8) != CountTotal) {
                throw new UnsupportedLayoutException("split central directory");
            }

            if (CdOffset == 0xffffffffL || CdSize == 0xffffffffL
                    || CountTotal == 0xffff) {
                if (TailStart < 20) {
                    throw new UnsupportedLayoutException("missing ZIP64 locator");
                }
                ByteBuffer Locator = Slice(Input, TailStart - 20, 20);
                if (U32(Locator, 0) != ZIP64_LOC_SIG || U32(Locator, 4) != 0
                        || U32(Locator, 16) != 1) {
                    throw new UnsupportedLayoutException("bad ZIP64 locator");
                }
                long Zip64Offset = Locator.getLong(8);
                ByteBuffer Zip64 = Slice(Input, Zip64Offset, 56);
                if (U32(Zip64, 0) != ZIP64_EOCD_SIG) {
                    throw new UnsupportedLayoutException("bad ZIP64 EOCD");
                }
                long Count = Zip64.getLong(32);
                if (Count > Integer.MAX_VALUE) {
                    throw new UnsupportedLayoutException("too many ZIP entries");
                }
                CountTotal = (int) Count;
                CdSize = Zip64.getLong(40);
                CdOffset = Zip64.getLong(48);
                TailStart = Math.min(TailStart, Zip64Offset);
            }
            if (CdOffset < 0 || CdSize < 0 || CdOffset + CdSize > TotalLength) {
                throw new UnsupportedLayoutException("central directory out of range");
            }

            byte[] CdRaw = new byte[(int) CdSize];
            Input.seek(CdOffset);
            Input.readFully(CdRaw);

            ByteBuffer Cd = ByteBuffer.wrap(CdRaw).order(ByteOrder.LITTLE_ENDIAN);
            List<Cde> Entries = new ArrayList<>();
            Set<String> Seen = new HashSet<>();
            for (int Index = 0; Index < CountTotal; Index++) {
                if (Cd.remaining() < 46 || U32(Cd, Cd.position()) != CDE_SIG) {
                    throw new UnsupportedLayoutException("corrupt central directory");
                }
                int Base = Cd.position();
                Cde Entry = new Cde();
                Entry.Flags = U16(Cd, Base + 8);
                Entry.Method = U16(Cd, Base + 10);
                Entry.Crc = U32(Cd, Base + 16);
                Entry.CompressedSize = U32(Cd, Base + 20);
                Entry.Size = U32(Cd, Base + 24);
                int NameLength = U16(Cd, Base + 28);
                int ExtraLength = U16(Cd, Base + 30);
                int CommentLength = U16(Cd, Base + 32);
                Entry.LocalOffset = U32(Cd, Base + 42);
                Cd.position(Base + 46);
                Entry.NameBytes = new byte[NameLength];
                Cd.get(Entry.NameBytes);
                byte[] Extra = new byte[ExtraLength];
                Cd.get(Extra);
                Cd.position(Cd.position() + CommentLength);

                if ((Entry.Flags & FLAG_ENCRYPTED) != 0) {
                    throw new UnsupportedLayoutException("encrypted ZIP entry");
                }
                if (Entry.Size == 0xffffffffL || Entry.CompressedSize == 0xffffffffL
                        || Entry.LocalOffset == 0xffffffffL) {
                    ByteBuffer Ex = ByteBuffer.wrap(Extra)
                            .order(ByteOrder.LITTLE_ENDIAN);
                    boolean Found = false;
                    while (Ex.remaining() >= 4) {
                        int Tag = U16(Ex, Ex.position());
                        int BlockLength = U16(Ex, Ex.position() + 2);
                        Ex.position(Ex.position() + 4);
                        if (BlockLength < 0 || BlockLength > Ex.remaining()) {
                            throw new UnsupportedLayoutException("corrupt ZIP64 extra");
                        }
                        if (Tag == 0x0001) {
                            ByteBuffer Values = Ex.slice()
                                    .order(ByteOrder.LITTLE_ENDIAN);
                            if (Entry.Size == 0xffffffffL) {
                                Entry.Size = Values.getLong();
                            }
                            if (Entry.CompressedSize == 0xffffffffL) {
                                Entry.CompressedSize = Values.getLong();
                            }
                            if (Entry.LocalOffset == 0xffffffffL) {
                                Entry.LocalOffset = Values.getLong();
                            }
                            Found = true;
                            break;
                        }
                        Ex.position(Ex.position() + BlockLength);
                    }
                    if (!Found) {
                        throw new UnsupportedLayoutException("missing ZIP64 extra");
                    }
                }
                Entry.Name = new String(Entry.NameBytes, NameCharset(Entry.Flags));
                if (!Seen.add(new String(Entry.NameBytes,
                        StandardCharsets.ISO_8859_1))) {
                    throw new UnsupportedLayoutException("duplicate ZIP entry name");
                }
                Entries.add(Entry);
            }
            if (Cd.remaining() != 0) {
                throw new UnsupportedLayoutException("central directory size mismatch");
            }

            Entries.sort(Comparator.comparingLong(Entry -> Entry.LocalOffset));
            byte[] Prefix;
            if (Entries.isEmpty() || Entries.get(0).LocalOffset == 0) {
                Prefix = new byte[0];
            } else {
                Prefix = new byte[(int) Entries.get(0).LocalOffset];
                Input.seek(0);
                Input.readFully(Prefix);
            }

            List<Plan> Plans = new ArrayList<>(Entries.size());
            long PreviousEnd = Prefix.length;
            for (int Index = 0; Index < Entries.size(); Index++) {
                Cde Entry = Entries.get(Index);
                if (Entry.LocalOffset < PreviousEnd) {
                    throw new UnsupportedLayoutException("overlapping ZIP entries");
                }
                long SpanEnd = Index + 1 < Entries.size()
                        ? Entries.get(Index + 1).LocalOffset : CdOffset;
                ByteBuffer Local = Slice(Input, Entry.LocalOffset, 30);
                if (U32(Local, 0) != LFH_SIG) {
                    throw new UnsupportedLayoutException("bad local header");
                }
                long PayloadStart = Entry.LocalOffset + 30L
                        + U16(Local, 26) + U16(Local, 28);
                Plan Item = new Plan();
                Item.Entry = Entry;
                Item.Offset = Entry.LocalOffset;
                Item.PrefixEnd = PayloadStart;
                Item.PayloadEnd = PayloadStart + Entry.CompressedSize;
                Item.End = SpanEnd;
                if (Item.PayloadEnd > SpanEnd) {
                    throw new UnsupportedLayoutException("entry payload out of range");
                }
                Item.Raw = IsDropped.test(Entry.Name)
                        || (Entry.Flags & FLAG_DESCRIPTOR) != 0
                        || Entry.Method != METHOD_STORED;
                Plans.add(Item);
                PreviousEnd = SpanEnd;
            }

            long SigningOffset = Plans.isEmpty() ? Prefix.length
                    : Plans.get(Plans.size() - 1).End;
            if (SigningOffset > CdOffset) {
                throw new UnsupportedLayoutException("signing block out of range");
            }

            try (OutputStream Stream = new BufferedOutputStream(
                    new FileOutputStream(Destination))) {
                WriteInt(Stream, MAGIC);
                WriteInt(Stream, VERSION);
                WriteLong(Stream, TotalLength);
                Stream.write(Sha256(Original));
                WritePooled(Stream, Input, 0, Prefix.length);
                WritePooled(Stream, Input, SigningOffset, CdOffset - SigningOffset);
                WritePooled(Stream, Input, CdOffset, CdSize);
                WritePooled(Stream, Input, TailStart, TotalLength - TailStart);
                WriteInt(Stream, Plans.size());
                for (Plan Item : Plans) {
                    Stream.write(Item.Raw ? 1 : 0);
                    WriteInt(Stream, Item.Entry.Flags);
                    if (Item.Raw) {
                        WriteInt(Stream, 0); // no name needed for raw spans
                    } else {
                        WriteInt(Stream, Item.Entry.NameBytes.length);
                        Stream.write(Item.Entry.NameBytes);
                    }
                    WriteLong(Stream, Item.Entry.Size);
                    WriteLong(Stream, Item.Entry.Crc);
                    // RAW entries stream the whole span (LFH, payload, descriptor,
                    // padding) as one block; OUTER entries stream header + suffix
                    // only, with the payload recovered from the outer APK.
                    WritePooled(Stream, Input, Item.Offset,
                            Item.Raw ? 0 : Item.PrefixEnd - Item.Offset);
                    WritePooled(Stream, Input, Item.Offset,
                            Item.Raw ? Item.End - Item.Offset : 0);
                    WritePooled(Stream, Input, Item.PayloadEnd,
                            Item.Raw ? 0 : Item.End - Item.PayloadEnd);
                }
            }
        }
    }

    /**
     * Rebuilds the byte-identical original APK from a slim container and the
     * patched outer APK ({@code OuterApk} supplies the STORED entry payloads).
     *
     * @param VerifyDigest when true, the rebuilt bytes are hashed and compared
     *                     against the SHA-256 digest recorded in the container.
     */
    public static void rebuild(File OuterApk, InputStream Container,
                               File Destination, boolean VerifyDigest)
            throws IOException {
        File Temporary = new File(Destination.getParentFile(),
                Destination.getName() + ".tmp");
        MessageDigest Digest = VerifyDigest ? NewDigest() : null;
        boolean Done = false;
        try (DataInputStream Input = new DataInputStream(Container);
             ZipFile Outer = new ZipFile(OuterApk);
             OutputStream Output = new BufferedOutputStream(
                     new FileOutputStream(Temporary))) {
            if (Input.readInt() != MAGIC || Input.readInt() != VERSION) {
                throw new IOException("unsupported slim container");
            }
            long ExpectedSize = Input.readLong();
            byte[] ExpectedDigest = new byte[32];
            Input.readFully(ExpectedDigest);

            TrackingStream Counter = new TrackingStream(Output, Digest);
            StreamBlock(Input, Counter, -1); // prefix -> written immediately
            // These blocks are written only after every entry span, so buffer
            // them; they are the signing block, central directory and tail.
            byte[] Signing = ReadBlockBytes(Input);
            byte[] CdBlock = ReadBlockBytes(Input);
            byte[] Tail = ReadBlockBytes(Input);

            int Count = Input.readInt();
            if (Count < 0) {
                throw new IOException("corrupt slim container");
            }
            for (int Index = 0; Index < Count; Index++) {
                int Raw = Input.read();
                if (Raw < 0) {
                    throw new EOFException("truncated slim container");
                }
                int Flags = Input.readInt(); // ZIP flags of the original entry
                int NameLength = Input.readInt();
                if (NameLength < 0 || NameLength > 65535) {
                    throw new IOException("corrupt slim container");
                }
                byte[] NameBytes = new byte[NameLength];
                Input.readFully(NameBytes);
                long ExpectedEntrySize = Input.readLong();
                long ExpectedCrc = Input.readLong();

                if (Raw == 1) {
                    StreamBlock(Input, Counter, -1);   // local header (uncopied)
                    StreamBlock(Input, Counter, -1);   // span, streamed to output
                    StreamBlock(Input, Counter, 0);    // suffix (always empty)
                } else {
                    StreamBlock(Input, Counter, -1);   // local header
                    StreamBlock(Input, Counter, 0);    // empty span placeholder
                    String Name = new String(NameBytes, NameCharset(Flags));
                    ZipEntry Source = Outer.getEntry(Name);
                    if (Source == null || Source.getSize() != ExpectedEntrySize) {
                        throw new IOException("outer APK entry unusable: " + Name);
                    }
                    CRC32 Crc = new CRC32();
                    try (InputStream Content = Outer.getInputStream(Source)) {
                        Copy(Content, Counter, ExpectedEntrySize, Crc);
                    }
                    if (Crc.getValue() != ExpectedCrc) {
                        throw new IOException("outer APK content mismatch: " + Name);
                    }
                    StreamBlock(Input, Counter, -1);   // suffix
                }
            }
            Counter.write(Signing);
            Counter.write(CdBlock);
            Counter.write(Tail);
            Counter.flush();
            if (Counter.Total != ExpectedSize) {
                throw new IOException("rebuilt APK size mismatch");
            }
            if (VerifyDigest
                    && !MessageDigest.isEqual(ExpectedDigest, Digest.digest())) {
                throw new IOException("rebuilt APK digest mismatch");
            }
            Done = true;
        } finally {
            if (!Done) {
                Temporary.delete();
            }
        }
        if (Destination.exists() && !Destination.delete()) {
            throw new IOException("could not replace the existing APK");
        }
        if (!Temporary.renameTo(Destination)) {
            throw new IOException("could not finalize the rebuilt APK");
        }
    }

    /** SHA-256 of a file. Used for patch-time self-verification. */
    public static byte[] Sha256(File Source) throws IOException {
        MessageDigest Digest = NewDigest();
        try (InputStream Input = new FileInputStream(Source)) {
            byte[] Buffer = new byte[65536];
            int Read;
            while ((Read = Input.read(Buffer)) != -1) {
                Digest.update(Buffer, 0, Read);
            }
        }
        return Digest.digest();
    }

    /** True when both files have identical SHA-256 digests. */
    public static boolean SameDigest(File Left, File Right) throws IOException {
        return MessageDigest.isEqual(Sha256(Left), Sha256(Right));
    }

    /** Streams {@code Count} bytes, updating {@code Crc}. */
    private static void Copy(InputStream Input, OutputStream Output, long Count,
                             CRC32 Crc) throws IOException {
        byte[] Buffer = new byte[65536];
        long Remaining = Count;
        while (Remaining > 0) {
            int Read = Input.read(Buffer, 0,
                    (int) Math.min(Buffer.length, Remaining));
            if (Read < 0) {
                throw new EOFException("truncated outer APK entry");
            }
            Output.write(Buffer, 0, Read);
            Crc.update(Buffer, 0, Read);
            Remaining -= Read;
        }
    }

    private static MessageDigest NewDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException Error) {
            throw new IllegalStateException(Error);
        }
    }

    private static Charset NameCharset(int Flags) {
        return (Flags & FLAG_UTF8) != 0 ? StandardCharsets.UTF_8
                : Charset.forName("IBM437");
    }

    /** Finds the End Of Central Directory record offset. */
    private static long FindEocd(RandomAccessFile Input) throws IOException {
        long Length = Input.length();
        if (Length < 22) {
            throw new UnsupportedLayoutException("file too small for a ZIP");
        }
        int Window = (int) Math.min(Length, 65536 + 22);
        byte[] Tail = new byte[Window];
        Input.seek(Length - Window);
        Input.readFully(Tail);
        for (int Index = Window - 22; Index >= 0; Index--) {
            if ((Tail[Index] & 0xff) == 0x50 && (Tail[Index + 1] & 0xff) == 0x4b
                    && (Tail[Index + 2] & 0xff) == 0x05
                    && (Tail[Index + 3] & 0xff) == 0x06) {
                int Comment = (Tail[Index + 20] & 0xff)
                        | (Tail[Index + 21] & 0xff) << 8;
                if (Index + 22 + Comment == Window) {
                    return Length - Window + Index;
                }
            }
        }
        throw new UnsupportedLayoutException("no end of central directory");
    }

    private static ByteBuffer Slice(RandomAccessFile Input, long Offset,
                                    long Length) throws IOException {
        if (Offset < 0 || Length < 0 || Length > Integer.MAX_VALUE
                || Offset + Length > Input.length()) {
            throw new UnsupportedLayoutException("ZIP slice out of range");
        }
        byte[] Data = new byte[(int) Length];
        Input.seek(Offset);
        Input.readFully(Data);
        return ByteBuffer.wrap(Data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int U16(ByteBuffer Buffer, int Offset) {
        return Buffer.getShort(Offset) & 0xffff;
    }

    private static long U32(ByteBuffer Buffer, int Offset) {
        return Buffer.getInt(Offset) & 0xffffffffL;
    }

    private static void WriteInt(OutputStream Output, int Value)
            throws IOException {
        Output.write((Value >>> 24) & 0xff);
        Output.write((Value >>> 16) & 0xff);
        Output.write((Value >>> 8) & 0xff);
        Output.write(Value & 0xff);
    }

    private static void WriteLong(OutputStream Output, long Value)
            throws IOException {
        WriteInt(Output, (int) (Value >>> 32));
        WriteInt(Output, (int) Value);
    }

    /**
     * Writes a length-prefixed block, streaming {@code Length} bytes from
     * {@code Offset} of the random-access source when {@code Length} is large.
     */
    private static void WritePooled(OutputStream Output, RandomAccessFile Source,
                                    long Offset, long Length) throws IOException {
        WriteLong(Output, Length);
        if (Length == 0) {
            return;
        }
        Source.seek(Offset);
        byte[] Buffer = new byte[(int) Math.min(65536, Length)];
        long Remaining = Length;
        while (Remaining > 0) {
            int Read = Source.read(Buffer, 0,
                    (int) Math.min(Buffer.length, Remaining));
            if (Read < 0) {
                throw new EOFException("unexpected end of source APK");
            }
            Output.write(Buffer, 0, Read);
            Remaining -= Read;
        }
    }

    /** Reads one length-prefixed block into memory (bounded to 256 MB). */
    private static byte[] ReadBlockBytes(DataInputStream Input)
            throws IOException {
        long Length = Input.readLong();
        if (Length < 0 || Length > 0x10000000L) {
            throw new IOException("corrupt slim container block");
        }
        byte[] Block = new byte[(int) Length];
        Input.readFully(Block);
        return Block;
    }

    /**
     * Streams one length-prefixed block from a container to the output. When
     * {@code ExpectLength} is non-negative the block must match it exactly.
     */
    private static void StreamBlock(DataInputStream Input, OutputStream Output,
                                    long ExpectLength) throws IOException {
        long Length = Input.readLong();
        if (Length < 0 || (ExpectLength >= 0 && Length != ExpectLength)) {
            throw new IOException("corrupt slim container block");
        }
        byte[] Buffer = new byte[(int) Math.min(65536, Math.max(Length, 1))];
        long Remaining = Length;
        while (Remaining > 0) {
            int Read = Input.read(Buffer, 0,
                    (int) Math.min(Buffer.length, Remaining));
            if (Read < 0) {
                throw new EOFException("truncated slim container");
            }
            Output.write(Buffer, 0, Read);
            Remaining -= Read;
        }
    }

    /** Output stream that counts bytes and feeds an optional digest. */
    private static final class TrackingStream extends OutputStream {
        final OutputStream Output;
        final MessageDigest Digest;
        long Total;

        TrackingStream(OutputStream Output, MessageDigest Digest) {
            this.Output = Output;
            this.Digest = Digest;
        }

        @Override
        public void write(int Value) throws IOException {
            Output.write(Value);
            if (Digest != null) {
                Digest.update((byte) Value);
            }
            Total++;
        }

        @Override
        public void write(byte[] Buffer, int Offset, int Length)
                throws IOException {
            Output.write(Buffer, Offset, Length);
            if (Digest != null) {
                Digest.update(Buffer, Offset, Length);
            }
            Total += Length;
        }

        @Override
        public void flush() throws IOException {
            Output.flush();
        }
    }
}
