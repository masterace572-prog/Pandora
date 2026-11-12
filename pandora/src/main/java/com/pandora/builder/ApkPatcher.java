package com.pandora.builder;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.pandora.slim.SlimFormat;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.FilterOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ApkPatcher {
    private static final String PayloadFactory = "com.pandora.core.AppFactory";
    private static final String NativeLibrary = "lib/arm64-v8a/libpandora-core.so";
    private static final String EmbeddedApk = SlimFormat.LegacyPath;

    public static final class Result {
        public final String PackageName;
        public final int AddedDexFiles;
        /** True when the original APK rides as a slim container (no size doubling). */
        public final boolean SlimEmbed;

        private Result(String PackageName, int AddedDexFiles, boolean SlimEmbed) {
            this.PackageName = PackageName;
            this.AddedDexFiles = AddedDexFiles;
            this.SlimEmbed = SlimEmbed;
        }
    }

    private static final class ManifestResult {
        final byte[] Bytes;
        final String OriginalFactory;

        ManifestResult(byte[] Bytes, String OriginalFactory) {
            this.Bytes = Bytes;
            this.OriginalFactory = OriginalFactory;
        }
    }

    public Result Patch(File InputApk, InputStream PayloadApk, File OutputApk,
                        PackageManager Packages) throws IOException {
        if (InputApk == null || !InputApk.isFile() || OutputApk == null) {
            throw new IOException("Choose a valid input APK");
        }
        PackageInfo Package = Packages.getPackageArchiveInfo(InputApk.getAbsolutePath(), 0);
        if (Package == null || Package.packageName == null) {
            throw new IOException("The input APK has no readable package information");
        }

        File PayloadFile = File.createTempFile("pandora-payload-", ".apk", OutputApk.getParentFile());
        try {
            Files.copy(PayloadApk, PayloadFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return PatchArchives(InputApk, PayloadFile, OutputApk, Package.packageName);
        } finally {
            Files.deleteIfExists(PayloadFile.toPath());
        }
    }

    private Result PatchArchives(File InputApk, File PayloadFile, File OutputApk,
                                 String PackageName) throws IOException {
        File SlimContainer = null;
        try (ZipFile Input = new ZipFile(InputApk); ZipFile Payload = new ZipFile(PayloadFile)) {
            ZipEntry ManifestEntry = Input.getEntry("AndroidManifest.xml");
            ZipEntry LibraryEntry = Payload.getEntry(NativeLibrary);
            if (ManifestEntry == null || LibraryEntry == null || Input.getEntry("classes.dex") == null) {
                throw new IOException("The input or payload APK is incomplete");
            }
            if (Input.getEntry(EmbeddedApk) != null || Input.getEntry(SlimFormat.ContainerPath) != null
                    || Input.getEntry(NativeLibrary) != null) {
                throw new IOException("This APK already contains Pandora payload paths");
            }

            ManifestResult Manifest = RewriteManifest(Input.getInputStream(ManifestEntry));
            List<ZipEntry> PayloadDex = FindDexEntries(Payload);
            int NextDex = FindNextDexIndex(Input);
            if (PayloadDex.isEmpty()) {
                throw new IOException("The payload has no DEX files");
            }

            // Try the slim container first: the original APK is only stored for
            // the entries the outer APK cannot reproduce byte-exactly. The
            // container is verified by rebuilding the original right here, so an
            // unusable layout can never reach a device.
            SlimContainer = TryBuildSlim(InputApk);

            CountingOutputStream Counted = new CountingOutputStream(new FileOutputStream(OutputApk));
            try (ZipOutputStream Output = new ZipOutputStream(Counted)) {
                Output.setLevel(Deflater.BEST_COMPRESSION);
                // Track every name written: APKs produced by some repack tools
                // contain duplicated entry names (e.g. two classes2.dex), which
                // would otherwise fail deep inside the build with a bare
                // ZipException from ZipOutputStream.
                Set<String> Written = new HashSet<>();
                Enumeration<? extends ZipEntry> Entries = Input.entries();
                while (Entries.hasMoreElements()) {
                    ZipEntry Entry = Entries.nextElement();
                    String Name = Entry.getName();
                    if (Name.equals("AndroidManifest.xml") || IsSigningMetadata(Name)) {
                        continue;
                    }
                    if (!Written.add(Name)) {
                        continue; // duplicated archive entry: keep the first copy
                    }
                    if (Name.equals("resources.arsc")) {
                        CopyStoredEntry(Input.getInputStream(Entry), Output, Counted, Entry);
                    } else {
                        CopyEntry(Input.getInputStream(Entry), Output, Name);
                    }
                }

                ReservedEntry(Written, "AndroidManifest.xml");
                AddEntry(Output, "AndroidManifest.xml", Manifest.Bytes);
                if (SlimContainer != null) {
                    ReservedEntry(Written, SlimFormat.ContainerPath);
                    CopyEntry(new FileInputStream(SlimContainer), Output, SlimFormat.ContainerPath);
                } else {
                    ReservedEntry(Written, EmbeddedApk);
                    try (InputStream Original = new FileInputStream(InputApk)) {
                        CopyEntry(Original, Output, EmbeddedApk);
                    }
                }
                if (Manifest.OriginalFactory != null) {
                    ReservedEntry(Written, "assets/pandora/original_factory.txt");
                    AddEntry(Output, "assets/pandora/original_factory.txt",
                            Manifest.OriginalFactory.getBytes(StandardCharsets.UTF_8));
                }

                for (ZipEntry Entry : PayloadDex) {
                    String Name = NextDex == 1 ? "classes.dex" : "classes" + NextDex + ".dex";
                    ReservedEntry(Written, Name);
                    CopyEntry(Payload.getInputStream(Entry), Output, Name);
                    NextDex++;
                }
                ReservedEntry(Written, NativeLibrary);
                CopyEntry(Payload.getInputStream(LibraryEntry), Output, NativeLibrary);
            }

            try (ZipFile Result = new ZipFile(OutputApk)) {
                if (Result.getEntry("AndroidManifest.xml") == null ||
                        Result.getEntry(NativeLibrary) == null ||
                        (Result.getEntry(SlimFormat.ContainerPath) == null &&
                                Result.getEntry(EmbeddedApk) == null)) {
                    throw new IOException("The output APK failed validation");
                }
            }
            return new Result(PackageName, PayloadDex.size(), SlimContainer != null);
        } finally {
            if (SlimContainer != null) {
                SlimContainer.delete();
            }
        }
    }

    /**
     * Builds and self-verifies a slim container for the input APK. Returns null
     * (and cleans up) when the layout cannot be slimmed or the rebuilt bytes do
     * not match the original SHA-256; the caller then falls back to a full copy.
     */
    private static File TryBuildSlim(File InputApk) {
        File Container = null;
        File Rebuilt = null;
        try {
            Container = File.createTempFile("pandora-slim-", ".bin");
            SlimFormat.write(InputApk, ApkPatcher::IsDroppedEntry, Container);
            Rebuilt = File.createTempFile("pandora-slim-check-", ".apk");
            try (InputStream Source = new FileInputStream(Container)) {
                // The input doubles as the outer-APK stand-in: the kept entries
                // carry identical content in both files.
                SlimFormat.rebuild(InputApk, Source, Rebuilt, true);
            }
            if (!SlimFormat.SameDigest(InputApk, Rebuilt)) {
                throw new IOException("slim rebuild digest mismatch");
            }
            return Container;
        } catch (IOException | RuntimeException Error) {
            if (Container != null) {
                Container.delete();
            }
            return null;
        } finally {
            if (Rebuilt != null) {
                Rebuilt.delete();
            }
        }
    }

    /** Entries that vanish from the outer APK and must ride inside the container. */
    private static boolean IsDroppedEntry(String Name) {
        return Name.equals("AndroidManifest.xml") || IsSigningMetadata(Name);
    }

    /** Fails loudly instead of letting ZipOutputStream emit "duplicate entry". */
    private static void ReservedEntry(Set<String> Written, String Name) throws IOException {
        if (!Written.add(Name)) {
            throw new IOException("Output entry collision: " + Name);
        }
    }

    private static ManifestResult RewriteManifest(InputStream Input) throws IOException {
        AndroidManifestBlock Manifest;
        try (InputStream Source = Input) {
            Manifest = AndroidManifestBlock.load(Source);
        }
        ResXmlElement Application = Manifest.getApplicationElement();
        if (Application == null) {
            throw new IOException("The input APK has no application element");
        }
        String OriginalFactory = null;
        for (int Index = 0; Index < Application.getAttributeCount(); Index++) {
            ResXmlAttribute Attribute = Application.getAttributeAt(Index);
            if ("appComponentFactory".equals(Attribute.getName())) {
                OriginalFactory = Attribute.getValueString();
                if (OriginalFactory == null || OriginalFactory.isEmpty() ||
                        OriginalFactory.equals(PayloadFactory)) {
                    throw new IOException("The input component factory cannot be chained");
                }
                OriginalFactory = Manifest.fullClassName(OriginalFactory);
            }
        }

        Application.getOrCreateAndroidAttribute("appComponentFactory",
                android.R.attr.appComponentFactory).setValueAsString(PayloadFactory);
        Manifest.setExtractNativeLibs(true);
        Manifest.refreshFull();
        File Temporary = File.createTempFile("pandora-manifest-", ".xml");
        try {
            Manifest.writeBytes(Temporary);
            return new ManifestResult(Files.readAllBytes(Temporary.toPath()), OriginalFactory);
        } finally {
            Files.deleteIfExists(Temporary.toPath());
        }
    }

    private static List<ZipEntry> FindDexEntries(ZipFile Archive) {
        List<ZipEntry> Entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> All = Archive.entries();
        while (All.hasMoreElements()) {
            ZipEntry Entry = All.nextElement();
            if (IsDexName(Entry.getName())) {
                Entries.add(Entry);
            }
        }
        return Entries;
    }

    private static int FindNextDexIndex(ZipFile Archive) {
        int Highest = 0;
        Enumeration<? extends ZipEntry> Entries = Archive.entries();
        while (Entries.hasMoreElements()) {
            String Name = Entries.nextElement().getName();
            if (IsDexName(Name)) {
                int Index = Name.equals("classes.dex") ? 1
                        : Integer.parseInt(Name.substring(7, Name.length() - 4));
                Highest = Math.max(Highest, Index);
            }
        }
        return Highest + 1;
    }

    private static boolean IsDexName(String Name) {
        return Name.equals("classes.dex") || Name.matches("classes(?:[2-9]|[1-9][0-9]+)\\.dex");
    }

    private static boolean IsSigningMetadata(String Name) {
        if (!Name.startsWith("META-INF/")) {
            return false;
        }
        String Upper = Name.toUpperCase(Locale.ROOT);
        return Upper.equals("META-INF/MANIFEST.MF") || Upper.endsWith(".SF") ||
                Upper.endsWith(".RSA") || Upper.endsWith(".DSA") || Upper.endsWith(".EC");
    }

    private static void AddEntry(ZipOutputStream Output, String Name, byte[] Content)
            throws IOException {
        CopyEntry(new java.io.ByteArrayInputStream(Content), Output, Name);
    }

    private static void CopyEntry(InputStream Input, ZipOutputStream Output, String Name)
            throws IOException {
        try (InputStream Source = Input) {
            ZipEntry Entry = new ZipEntry(Name);
            Output.putNextEntry(Entry);
            Transfer(Source, Output);
            Output.closeEntry();
        }
    }

    private static void CopyStoredEntry(InputStream Input, ZipOutputStream Output,
                                        CountingOutputStream Counted, ZipEntry Original)
            throws IOException {
        try (InputStream Source = Input) {
            ZipEntry Entry = new ZipEntry(Original.getName());
            Entry.setMethod(ZipEntry.STORED);
            Entry.setSize(Original.getSize());
            Entry.setCompressedSize(Original.getSize());
            Entry.setCrc(Original.getCrc());

            int NameLength = Entry.getName().getBytes(StandardCharsets.UTF_8).length;
            int Padding = (int) ((4 - (Counted.Count + 30 + NameLength + 4) % 4) % 4);
            byte[] Extra = new byte[4 + Padding];
            Extra[0] = (byte) 0xfe;
            Extra[1] = (byte) 0xca;
            Extra[2] = (byte) Padding;
            Entry.setExtra(Extra);

            Output.putNextEntry(Entry);
            Transfer(Source, Output);
            Output.closeEntry();
        }
    }

    private static void Transfer(InputStream Input, OutputStream Output)
            throws IOException {
        byte[] Buffer = new byte[65536];
        int Count;
        while ((Count = Input.read(Buffer)) != -1) {
            Output.write(Buffer, 0, Count);
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long Count;

        private CountingOutputStream(OutputStream Output) {
            super(Output);
        }

        @Override
        public void write(int Value) throws IOException {
            out.write(Value);
            Count++;
        }

        @Override
        public void write(byte[] Buffer, int Offset, int Length) throws IOException {
            out.write(Buffer, Offset, Length);
            Count += Length;
        }
    }
}
