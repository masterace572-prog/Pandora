# AOSP source compatibility

| AOSP source symbol | [Android 10](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/core/java/) | [Android 11](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/) | [Android 12](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/) | [Android 13](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/) | [Android 14](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/) | [Android 15](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/) | [Android 16](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/) | [Android 17](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ActivityThread.currentActivityThread | yes | yes | yes | yes | yes | yes | yes | yes |
| ActivityThread.mBoundApplication | yes | yes | yes | yes | yes | yes | yes | yes |
| AppComponentFactory.instantiateClassLoader | yes | yes | yes | yes | yes | yes | yes | yes |
| AppComponentFactory.instantiateApplication | yes | yes | yes | yes | yes | yes | yes | yes |
| AppComponentFactory.instantiateActivity | yes | yes | yes | yes | yes | yes | yes | yes |
| AppComponentFactory.instantiateService | yes | yes | yes | yes | yes | yes | yes | yes |
| AppComponentFactory.instantiateReceiver | yes | yes | yes | yes | yes | yes | yes | yes |
| AppComponentFactory.instantiateProvider | yes | yes | yes | yes | yes | yes | yes | yes |

This report checks source symbol presence. It does not establish runtime access
to hidden APIs or device-specific behavior.

Sources were downloaded from the `android10-release` through
`android17-release` branches of AOSP frameworks/base on android.googlesource.com.
