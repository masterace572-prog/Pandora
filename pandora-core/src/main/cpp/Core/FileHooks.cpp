#include "FileHooks.hpp"
#include <cstdio>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>

namespace Pandora::FileHooks
{
    namespace
    {
        using EntryHook = Action (*)(SyscallContext &);
        using ExitHook = void (*)(SyscallContext &);

        struct Route
        {
            Arm64Syscall Number;
            EntryHook OnEntry;
            ExitHook OnExit;
        };

        constexpr Route Routes[] = { { Arm64Syscall::OpenAt, HookOpenAt, nullptr }, { Arm64Syscall::OpenAt2, HookOpenAt, nullptr }, { Arm64Syscall::ReadlinkAt, HookReadlinkAt, FinishReadlinkAt }, { Arm64Syscall::Fstat, HookFstat, FinishFstat }, { Arm64Syscall::NewFstatAt, HookNewFstatAt, FinishNewFstatAt }, { Arm64Syscall::Statx, HookStatx, FinishStatx } };

        GlobalState *ReadyState()
        {
            auto *State = GetGlobalState();
            return State && State->Initialized.load() ? State : nullptr;
        }

        std::int64_t GetFileSize(const char *Path)
        {
            struct stat Status;
            return stat(Path, &Status) == 0 ? Status.st_size : -1;
        }

        // bionic arm64 struct stat field offsets.
        constexpr std::size_t StatSizeOffset = 48;
        constexpr std::size_t StatBlocksOffset = 64;
        // struct statx field offsets.
        constexpr std::size_t StatxMaskOffset = 0;
        constexpr std::size_t StatxSizeOffset = 40;
        constexpr std::size_t StatxBlocksOffset = 48;

        void PatchStatFields(SyscallContext &Context, std::uint64_t Buffer, std::int64_t Size)
        {
            // Only the size-related fields are touched. Rewriting the whole
            // struct risked overrunning smaller caller buffers (struct stat is
            // 120 bytes in bionic; the previous code wrote 128).
            const std::uint64_t Blocks = ((std::uint64_t)Size + 511) >> 9;
            Context.Memory.Write(Buffer + StatSizeOffset, &Size, sizeof(Size));
            Context.Memory.Write(Buffer + StatBlocksOffset, &Blocks, sizeof(Blocks));
        }

        void PatchStatxFields(SyscallContext &Context, std::uint64_t Buffer, std::int64_t Size)
        {
            const std::uint64_t Blocks = ((std::uint64_t)Size + 511) >> 9;
            const std::uint32_t SizeFlag = 0x200; // STATX_SIZE
            std::uint32_t Mask = 0;
            if (Context.Memory.Read(Buffer + StatxMaskOffset, &Mask, sizeof(Mask)) == sizeof(Mask))
            {
                Mask |= SizeFlag;
                Context.Memory.Write(Buffer + StatxMaskOffset, &Mask, sizeof(Mask));
            }
            Context.Memory.Write(Buffer + StatxSizeOffset, &Size, sizeof(Size));
            Context.Memory.Write(Buffer + StatxBlocksOffset, &Blocks, sizeof(Blocks));
        }

        bool RewritePathArgument(SyscallContext &Context, std::uint64_t Address, std::size_t OriginalLength, const char *Replacement)
        {
            const auto Length = std::strlen(Replacement) + 1;
            if (Length <= OriginalLength + 1)
                return Context.Memory.Write(Address, Replacement, Length) == (ssize_t)Length;

            // The replacement path is longer than the caller's buffer: write it
            // to a scratch slot below the stack pointer and repoint the argument.
            // Several depths are tried because the page right below SP may not be
            // mapped yet, and deeper slots almost always are. If every attempt
            // fails, the syscall is left untouched; never corrupt the stack.
            static constexpr std::uint64_t Depths[] = { PathCapacity, 16384, 65536 };
            for (const auto Depth : Depths)
            {
                const auto Scratch = (Context.Registers.StackPointer & ~std::uint64_t{ 15 }) - Depth;
                if (Context.Memory.Write(Scratch, Replacement, Length) == (ssize_t)Length)
                {
                    Context.SetArgument(1, Scratch);
                    return Context.CommitRegisters();
                }
            }
            return false;
        }

        void RewriteInputPath(SyscallContext &Context)
        {
            auto *State = ReadyState();
            const auto Address = Context.Argument(1);
            if (!State || !Address)
                return;

            char Path[PathCapacity];
            const auto Length = Context.Memory.ReadCString(Address, Path);
            if (!Length)
                return;
            if (auto *Rule = State->FindRule(Path, Length, false))
                RewritePathArgument(Context, Address, Length, Rule->ReplacementPath);
        }
    }

    Action HookOpenAt(SyscallContext &Context)
    {
        RewriteInputPath(Context);
        return Action::Continue;
    }

    Action HookReadlinkAt(SyscallContext &)
    {
        return Action::TraceExit;
    }

    Action HookFstat(SyscallContext &)
    {
        return Action::TraceExit;
    }

    Action HookNewFstatAt(SyscallContext &Context)
    {
        RewriteInputPath(Context);
        return Action::TraceExit;
    }

    Action HookStatx(SyscallContext &Context)
    {
        RewriteInputPath(Context);
        return Action::TraceExit;
    }

    void FinishReadlinkAt(SyscallContext &Context)
    {
        auto *State = ReadyState();
        const auto Length = Context.Result();
        const auto Buffer = Context.OriginalArgument(2);
        const auto Capacity = Context.OriginalArgument(3);
        if (!State || !Buffer || Length <= 0 || Length >= (std::int64_t)PathCapacity || (std::uint64_t)Length > Capacity)
            return;

        char Path[PathCapacity];
        if (Context.Memory.Read(Buffer, Path, (std::size_t)Length) != Length)
            return;
        Path[Length] = '\0';
        const auto *Rule = State->FindRule(Path, (std::size_t)Length, true);
        if (!Rule || Rule->OriginalLength > Capacity)
            return;
        if (Context.Memory.Write(Buffer, Rule->OriginalPath, Rule->OriginalLength) != (ssize_t)Rule->OriginalLength)
            return;
        Context.SetResult((std::int64_t)Rule->OriginalLength);
        Context.CommitRegisters();
    }

    void FinishFstat(SyscallContext &Context)
    {
        auto *State = ReadyState();
        const auto Buffer = Context.OriginalArgument(1);
        if (!State || !Buffer || Context.Result() != 0)
            return;

        char Link[64];
        const auto LinkLength = std::snprintf(Link, sizeof(Link), "/proc/%d/fd/%d", Context.ThreadId, (int)Context.OriginalArgument(0));
        if (LinkLength < 0 || LinkLength >= (int)sizeof(Link))
            return;

        char Path[PathCapacity];
        const auto Length = readlink(Link, Path, PathCapacity - 1);
        if (Length <= 0)
            return;
        Path[Length] = '\0';
        const auto *Rule = State->FindRule(Path, (std::size_t)Length, true);
        if (!Rule)
            return;

        // The fd points at the embedded original bytes; report the original
        // size so fd reads and fd stats stay consistent with each other.
        const auto Size = GetFileSize(Rule->ReplacementPath);
        if (Size < 0)
            return;
        PatchStatFields(Context, Buffer, Size);
    }

    void FinishNewFstatAt(SyscallContext &Context)
    {
        auto *State = ReadyState();
        const auto Address = Context.OriginalArgument(1);
        const auto Buffer = Context.OriginalArgument(2);
        if (!State || !Address || !Buffer || Context.Result() != 0)
            return;

        char Path[PathCapacity];
        const auto Length = Context.Memory.ReadCString(Address, Path);
        const auto *Rule = Length ? State->FindRule(Path, Length, false) : nullptr;
        if (!Rule)
            return;

        const auto Size = GetFileSize(Rule->ReplacementPath);
        if (Size < 0)
            return;
        PatchStatFields(Context, Buffer, Size);
    }

    void FinishStatx(SyscallContext &Context)
    {
        auto *State = ReadyState();
        // Use the captured entry argument (the caller's original path), not the
        // rewritten scratch pointer: the scratch slot sits on the tracee's stack
        // and could be clobbered before the exit stop runs.
        const auto Address = Context.OriginalArgument(1);
        const auto Buffer = Context.OriginalArgument(4);
        if (!State || !Address || !Buffer || Context.Result() != 0)
            return;

        char Path[PathCapacity];
        const auto Length = Context.Memory.ReadCString(Address, Path);
        const auto *Rule = Length ? State->FindRule(Path, Length, false) : nullptr;
        if (!Rule)
            return;

        const auto Size = GetFileSize(Rule->ReplacementPath);
        if (Size < 0)
            return;
        PatchStatxFields(Context, Buffer, Size);
    }

    Action DispatchEntry(SyscallContext &Context)
    {
        for (const auto &Route : Routes)
            if (Route.Number == Context.Number())
                return Route.OnEntry(Context);
        return Action::Continue;
    }

    void DispatchExit(SyscallContext &Context)
    {
        for (const auto &Route : Routes)
            if (Route.Number == Context.Number())
            {
                if (Route.OnExit)
                    Route.OnExit(Context);
                return;
            }
    }
}
