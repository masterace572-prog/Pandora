package com.pandora.core;

import android.app.AppComponentFactory;
import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.pandora.slim.SlimFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AppFactory extends AppComponentFactory {
    private static final String LogTag = "Pandora";
    private volatile AppComponentFactory OriginalFactory;

    public static final class Data {
        public static String ApkPath;
        public static String ReplacementPath;

        private Data() {
        }
    }

    public static native void initRedirect(String OriginalPath, String ReplacementPath);
    public static native boolean isRedirectActive();
    public static native int getTracerPid();

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader Loader, ApplicationInfo Info) {
        OriginalFactory = CreateOriginalFactory(Loader, Info.sourceDir);
        return OriginalFactory == null
                ? super.instantiateClassLoader(Loader, Info)
                : OriginalFactory.instantiateClassLoader(Loader, Info);
    }

    @Override
    public Application instantiateApplication(ClassLoader Loader, String Name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateApplication(Loader, Name)
                : OriginalFactory.instantiateApplication(Loader, Name);
    }

    @Override
    public Activity instantiateActivity(ClassLoader Loader, String Name, Intent Intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateActivity(Loader, Name, Intent)
                : OriginalFactory.instantiateActivity(Loader, Name, Intent);
    }

    @Override
    public Service instantiateService(ClassLoader Loader, String Name, Intent Intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateService(Loader, Name, Intent)
                : OriginalFactory.instantiateService(Loader, Name, Intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader Loader, String Name, Intent Intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateReceiver(Loader, Name, Intent)
                : OriginalFactory.instantiateReceiver(Loader, Name, Intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader Loader, String Name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return OriginalFactory == null
                ? super.instantiateProvider(Loader, Name)
                : OriginalFactory.instantiateProvider(Loader, Name);
    }

    private AppComponentFactory CreateOriginalFactory(ClassLoader Loader, String ApkPath) {
        try (ZipFile Archive = new ZipFile(ApkPath)) {
            String Name = ReadAssetText(Archive, "assets/pandora/original_factory.txt", 512);
            if (Name == null) {
                return null;
            }
            return Loader.loadClass(Name.trim()).asSubclass(AppComponentFactory.class)
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | IOException Error) {
            throw new IllegalStateException("The original component factory could not be loaded", Error);
        }
    }

    static {
        try {
            Initialize();
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError Error) {
            Log.e(LogTag, "Initialization failed", Error);
        }
    }

    private static void Initialize() throws ReflectiveOperationException, IOException {
        ApplicationInfo Info = ReadApplicationInfo();
        if (Info == null || Info.sourceDir == null || Info.dataDir == null) {
            throw new IllegalStateException("Application information is unavailable");
        }

        Data.ApkPath = Info.sourceDir;
        Data.ReplacementPath = PrepareOriginalApk(Info);

        System.loadLibrary("pandora-core");
        initRedirect(Data.ApkPath, Data.ReplacementPath);
    }

    private static ApplicationInfo ReadApplicationInfo() throws ReflectiveOperationException {
        Class<?> ThreadClass = Class.forName("android.app.ActivityThread");
        Method CurrentThread = ThreadClass.getDeclaredMethod("currentActivityThread");
        Object Thread = CurrentThread.invoke(null);
        if (Thread == null) {
            return null;
        }

        Field BoundApplication = ThreadClass.getDeclaredField("mBoundApplication");
        BoundApplication.setAccessible(true);
        Object Bound = BoundApplication.get(Thread);
        if (Bound == null) {
            return null;
        }

        Field AppInfo = Bound.getClass().getDeclaredField("appInfo");
        AppInfo.setAccessible(true);
        return (ApplicationInfo) AppInfo.get(Bound);
    }

    /**
     * Materials the original APK bytes into the app's private data directory.
     *
     * <p>A slim container rebuilds the original byte-for-byte, recovering STORED
     * payloads from the installed APK itself. The legacy {@code assets/base.apk}
     * full copy is still honored. The result is cached: a sidecar marker keyed by
     * the installed APK's size and mtime lets cold starts skip the whole copy,
     * which removes the per-launch extraction stall entirely.</p>
     */
    private static String PrepareOriginalApk(ApplicationInfo Info) throws IOException {
        File Directory = new File(Info.dataDir);
        File Destination = new File(Directory, "base.apk");
        File Marker = new File(Directory, "base.apk.meta");
        File Source = new File(Info.sourceDir);
        String ExpectedKey = Source.length() + ":" + Source.lastModified();

        if (Destination.isFile() && ExpectedKey.equals(ReadMarker(Marker))
                && Destination.length() == MarkerLength(Marker)) {
            return Destination.getAbsolutePath();
        }

        try (ZipFile Archive = new ZipFile(Info.sourceDir)) {
            ZipEntry Slim = Archive.getEntry(SlimFormat.ContainerPath);
            if (Slim != null) {
                File Temporary = File.createTempFile("pandora-", ".apk", Directory);
                try {
                    try (InputStream Container = Archive.getInputStream(Slim)) {
                        SlimFormat.rebuild(Source, Container, Temporary, true);
                    }
                    MoveIntoPlace(Temporary, Destination);
                } finally {
                    Temporary.delete();
                }
            } else {
                ExtractOriginalApk(Archive, Directory, Destination);
            }
        }
        WriteMarker(Marker, ExpectedKey, Destination.length());
        return Destination.getAbsolutePath();
    }

    private static void ExtractOriginalApk(ZipFile Archive, File Directory,
                                           File Destination) throws IOException {
        ZipEntry Entry = Archive.getEntry(SlimFormat.LegacyPath);
        if (Entry == null) {
            throw new IOException("The embedded APK is missing");
        }

        File Temporary = File.createTempFile("pandora-", ".apk", Directory);
        try {
            try (InputStream Input = Archive.getInputStream(Entry)) {
                Files.copy(Input, Temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (Entry.getSize() >= 0 && Temporary.length() != Entry.getSize()) {
                throw new IOException("The embedded APK is incomplete");
            }
            MoveIntoPlace(Temporary, Destination);
        } finally {
            Temporary.delete();
        }
    }

    private static void MoveIntoPlace(File Temporary, File Destination)
            throws IOException {
        Files.move(Temporary.toPath(), Destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static String ReadMarker(File Marker) {
        try {
            byte[] Bytes = Files.readAllBytes(Marker.toPath());
            String Text = new String(Bytes, StandardCharsets.UTF_8);
            int Separator = Text.indexOf('|');
            return Separator > 0 ? Text.substring(0, Separator) : null;
        } catch (IOException | RuntimeException Error) {
            return null;
        }
    }

    private static long MarkerLength(File Marker) {
        try {
            byte[] Bytes = Files.readAllBytes(Marker.toPath());
            String Text = new String(Bytes, StandardCharsets.UTF_8);
            int Separator = Text.indexOf('|');
            return Separator > 0 ? Long.parseLong(Text.substring(Separator + 1)) : -1;
        } catch (IOException | RuntimeException Error) {
            return -1;
        }
    }

    private static void WriteMarker(File Marker, String Key, long Length)
            throws IOException {
        File Temporary = new File(Marker.getParentFile(), Marker.getName() + ".tmp");
        try (OutputStream Output = new FileOutputStream(Temporary)) {
            Output.write((Key + "|" + Length).getBytes(StandardCharsets.UTF_8));
        }
        if (!Temporary.renameTo(Marker)) {
            throw new IOException("The cache marker could not be written");
        }
    }

    private static String ReadAssetText(ZipFile Archive, String Name, int Limit)
            throws IOException {
        ZipEntry Entry = Archive.getEntry(Name);
        if (Entry == null) {
            return null;
        }
        if (Entry.getSize() > Limit) {
            throw new IOException("Payload metadata exceeds its limit");
        }
        try (InputStream Input = Archive.getInputStream(Entry)) {
            ByteArrayOutputStream Output = new ByteArrayOutputStream();
            byte[] Buffer = new byte[4096];
            int Count;
            while ((Count = Input.read(Buffer)) != -1) {
                if (Output.size() + Count > Limit) {
                    throw new IOException("Payload metadata exceeds its limit");
                }
                Output.write(Buffer, 0, Count);
            }
            return new String(Output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
