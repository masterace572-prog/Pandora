#pragma once

namespace Pandora
{
    bool Install(const char *OwnApkPath, const char *RedirectApkPath);
    bool IsActive();
    int TracerPid();
}

namespace pandora
{
    bool install(const char *ownApkPath, const char *redirectApkPath);
    bool active();
    int tracer_pid();
}

extern "C"
{
    int pandora_install(const char *own_apk_path, const char *redirect_apk_path);
    int pandora_active(void);
    int pandora_tracer_pid(void);
}
