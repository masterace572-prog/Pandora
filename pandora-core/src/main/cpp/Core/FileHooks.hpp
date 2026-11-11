#pragma once
#include "Structures.hpp"

namespace Pandora
{
    namespace FileHooks
    {
        enum class Action : std::uint8_t
        {
            Continue,
            TraceExit
        };

        Action HookOpenAt(SyscallContext &Context);
        Action HookReadlinkAt(SyscallContext &Context);
        Action HookFstat(SyscallContext &Context);
        Action HookNewFstatAt(SyscallContext &Context);
        Action HookStatx(SyscallContext &Context);

        void FinishReadlinkAt(SyscallContext &Context);
        void FinishFstat(SyscallContext &Context);
        void FinishNewFstatAt(SyscallContext &Context);
        void FinishStatx(SyscallContext &Context);

        Action DispatchEntry(SyscallContext &Context);
        void DispatchExit(SyscallContext &Context);
    }
}
