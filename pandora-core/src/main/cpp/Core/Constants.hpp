#pragma once
#include <cstddef>
#include <cstdint>

namespace Pandora
{
    constexpr std::size_t PathCapacity = 4096;
    constexpr std::size_t RuleCapacity = 128;
    constexpr std::size_t HashCapacity = 256;
    constexpr std::size_t ThreadCapacity = 512;
    constexpr std::uint64_t FnvOffset = 0xcbf29ce484222325ULL;
    constexpr std::uint64_t FnvPrime = 0x100000001b3ULL;
    constexpr unsigned long TraceOptions = 0x9f;
    constexpr int WaitAllThreads = 0x40000000;
    constexpr int WaitNoThread = 0x20000000;
    enum class RedirectState : std::uint8_t
    {
        Idle = 0,
        Starting = 1,
        Active = 2,
        Failed = 3
    };
    enum class Arm64Syscall : std::uint64_t
    {
        OpenAt = 56,
        ReadlinkAt = 78,
        NewFstatAt = 79,
        Fstat = 80,
        ProcessVmReadv = 270,
        ProcessVmWritev = 271,
        Seccomp = 277,
        Statx = 291,
        OpenAt2 = 437
    };
}
