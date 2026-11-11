#include <jni.h>

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *Vm, void *)
{
    JNIEnv *Environment = nullptr;
    if (Vm->GetEnv((void **)&Environment, JNI_VERSION_1_4) != JNI_OK)
        return JNI_ERR;
    return JNI_VERSION_1_4;
}
