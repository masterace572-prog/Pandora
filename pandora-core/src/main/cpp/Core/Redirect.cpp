#include "pandora/Redirect.hpp"
#include "Structures.hpp"
#include "Tracer.hpp"
#include <mutex>

namespace Pandora
{
    int InitializeRedirect(const char *OriginalPath, const char *ReplacementPath)
    {
        if (!OriginalPath || !ReplacementPath || OriginalPath[0] != '/' || ReplacementPath[0] != '/')
            return -1;
        static std::mutex InstallMutex;
        std::lock_guard<std::mutex> Lock(InstallMutex);
        if (!EnsureGlobalState())
            return -1;
        auto *Shared = GetGlobalState();
        const auto State = Shared->State.load();
        if (State == RedirectState::Starting || State == RedirectState::Active)
            return -1;
        if (!Shared->AddRule(OriginalPath, ReplacementPath))
            return -1;
        Shared->TracerPid.store(0);
        SetState(RedirectState::Starting);
        return StartTraceAsync();
    }

    bool IsRedirectActive()
    {
        auto *Shared = GetGlobalState();
        return Shared && Shared->State.load() == RedirectState::Active;
    }

    pid_t GetTracerPid()
    {
        auto *Shared = GetGlobalState();
        return Shared ? Shared->TracerPid.load() : 0;
    }

    bool Install(const char *OwnApkPath, const char *RedirectApkPath)
    {
        return InitializeRedirect(OwnApkPath, RedirectApkPath) == 0;
    }

    bool IsActive()
    {
        return IsRedirectActive();
    }

    int TracerPid()
    {
        return (int)GetTracerPid();
    }
}

namespace pandora
{
    bool install(const char *ownApkPath, const char *redirectApkPath)
    {
        return Pandora::Install(ownApkPath, redirectApkPath);
    }

    bool active()
    {
        return Pandora::IsActive();
    }

    int tracer_pid()
    {
        return Pandora::TracerPid();
    }
}

extern "C"
{
    int pandora_install(const char *own_apk_path, const char *redirect_apk_path)
    {
        return Pandora::InitializeRedirect(own_apk_path, redirect_apk_path);
    }

    int pandora_active(void)
    {
        return Pandora::IsRedirectActive() ? 1 : 0;
    }

    int pandora_tracer_pid(void)
    {
        return (int)Pandora::GetTracerPid();
    }
}
