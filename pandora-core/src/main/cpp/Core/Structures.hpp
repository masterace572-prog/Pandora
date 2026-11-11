#pragma once
#include "Constants.hpp"
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <sys/types.h>

namespace Pandora
{
    struct RedirectRule
    {
        char OriginalPath[PathCapacity];
        char ReplacementPath[PathCapacity];
        std::uint64_t OriginalLength;
        std::uint64_t ReplacementLength;
        std::uint64_t OriginalHash;
        std::uint64_t ReplacementHash;
        bool Enabled;
        std::uint8_t AlignmentPadding[7];
    };
    static_assert(sizeof(RedirectRule) == 8232);
    struct GlobalState
    {
        RedirectRule Rules[RuleCapacity];
        std::int32_t OriginalIndex[HashCapacity];
        std::int32_t ReplacementIndex[HashCapacity];
        std::atomic<std::uint64_t> RuleCount;
        std::atomic<bool> Initialized;
        std::atomic<RedirectState> State;
        std::uint8_t AlignmentPadding[2];
        std::atomic<pid_t> TracerPid;
        bool AddRule(const char *Original, const char *Replacement);
        const RedirectRule *FindRule(const char *Path, std::size_t Length, bool Reverse) const;
    };
    static_assert(offsetof(GlobalState, OriginalIndex) == 1053696);
    static_assert(offsetof(GlobalState, ReplacementIndex) == 1054720);
    static_assert(offsetof(GlobalState, RuleCount) == 1055744);
    static_assert(offsetof(GlobalState, Initialized) == 1055752);
    static_assert(offsetof(GlobalState, State) == 1055753);
    static_assert(offsetof(GlobalState, TracerPid) == 1055756);
    static_assert(sizeof(GlobalState) == 0x101c10);

    struct Arm64Registers
    {
        std::uint64_t General[31];
        std::uint64_t StackPointer;
        std::uint64_t ProgramCounter;
        std::uint64_t ProcessorState;
    };
    static_assert(sizeof(Arm64Registers) == 272);
    struct ProcessMemory
    {
        pid_t ProcessId;
        ssize_t Read(std::uint64_t Address, void *Buffer, std::size_t Length) const;
        ssize_t Write(std::uint64_t Address, const void *Buffer, std::size_t Length) const;
        std::size_t ReadCString(std::uint64_t Address, char (&Buffer)[PathCapacity]) const;
    };
    struct alignas(16) SyscallContext
    {
        pid_t ThreadId;
        std::uint8_t ReservedHeader[12];
        Arm64Registers Registers;
        ProcessMemory Memory;
        std::array<std::uint64_t, 6> EntryArguments{};
        std::uint64_t Argument(std::size_t Index) const
        {
            return Registers.General[Index];
        }
        std::uint64_t OriginalArgument(std::size_t Index) const
        {
            return EntryArguments[Index];
        }
        void SetArgument(std::size_t Index, std::uint64_t Value)
        {
            Registers.General[Index] = Value;
        }
        std::int64_t Result() const
        {
            return (std::int64_t)Registers.General[0];
        }
        void SetResult(std::int64_t Value)
        {
            Registers.General[0] = (std::uint64_t)Value;
        }
        Arm64Syscall Number() const
        {
            return (Arm64Syscall)Registers.General[8];
        }
        bool CommitRegisters();
    };
    static_assert(offsetof(SyscallContext, Registers) == 16);
    static_assert(offsetof(SyscallContext, Memory) == 288);
    struct AttachResult
    {
        pid_t Threads[ThreadCapacity]{};
        int Count = 0;
        bool Success = false;
        bool Contains(pid_t ThreadId) const;
        void DetachAll() const;
    };
    static_assert(offsetof(AttachResult, Success) == 2052);
    struct ThreadTraceState
    {
        bool OptionsSet = false;
        bool AwaitingExit = false;
        std::array<std::uint64_t, 6> EntryArguments{};
    };

    GlobalState *GetGlobalState();
    bool EnsureGlobalState();
    void SetState(RedirectState State);
    std::uint64_t HashPath(const char *Path, std::size_t Length);
}
