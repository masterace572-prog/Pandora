package com.pandora.builder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;

@RunWith(AndroidJUnit4.class)
public final class CoreRuntimeTest {
    @Test
    public void RedirectsApkReadsAfterActivation() throws Exception {
        Context App = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Class<?> Factory;
        try {
            Factory = App.getClassLoader().loadClass("com.pandora.core.AppFactory");
        } catch (ClassNotFoundException Error) {
            if ("true".equals(InstrumentationRegistry.getArguments().getString("requireCore"))) {
                throw Error;
            }
            return;
        }

        Method IsActive = Factory.getMethod("isRedirectActive");
        boolean Active = false;
        for (int Attempt = 0; Attempt < 100; Attempt++) {
            Active = (Boolean) IsActive.invoke(null);
            if (Active) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue("The native tracer did not activate", Active);

        File InstalledApk = new File(App.getApplicationInfo().sourceDir);
        File OriginalApk = new File(App.getApplicationInfo().dataDir, "base.apk");
        assertTrue(OriginalApk.isFile());
        assertEquals(OriginalApk.length(), InstalledApk.length());
        assertArrayEquals(Hash(OriginalApk), Hash(InstalledApk));
    }

    private static byte[] Hash(File Source) throws Exception {
        MessageDigest Digest = MessageDigest.getInstance("SHA-256");
        try (InputStream Input = new FileInputStream(Source)) {
            byte[] Buffer = new byte[65536];
            int Count;
            while ((Count = Input.read(Buffer)) != -1) {
                Digest.update(Buffer, 0, Count);
            }
        }
        return Digest.digest();
    }
}
