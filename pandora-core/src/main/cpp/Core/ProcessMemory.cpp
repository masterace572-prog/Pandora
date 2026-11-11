#include "Structures.hpp"
#include <algorithm>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <unistd.h>

namespace Pandora
{
    ssize_t ProcessMemory::Read(std::uint64_t Address, void *Buffer, std::size_t Length) const
    {
        if (!Length)
            return 0;
        iovec Local{ Buffer, Length }, Remote{ (void *)Address, Length };
        return syscall((long)Arm64Syscall::ProcessVmReadv, ProcessId, &Local, 1, &Remote, 1, 0);
    }

    ssize_t ProcessMemory::Write(std::uint64_t Address, const void *Buffer, std::size_t Length) const
    {
        if (!Length)
            return 0;
        iovec Local{ (void *)Buffer, Length }, Remote{ (void *)Address, Length };
        return syscall((long)Arm64Syscall::ProcessVmWritev, ProcessId, &Local, 1, &Remote, 1, 0);
    }

    std::size_t ProcessMemory::ReadCString(std::uint64_t Address, char (&Buffer)[PathCapacity]) const
    {
        std::size_t Length = 0;
        while (Length < PathCapacity - 1)
        {
            const auto Count = Read(Address + Length, Buffer + Length, std::min<std::size_t>(64, PathCapacity - 1 - Length));
            if (Count < 1)
                break;
            for (ssize_t Index = 0; Index < Count; ++Index)
                if (!Buffer[Length + Index])
                    return Length + Index;
            Length += Count;
        }
        Buffer[Length] = '\0';
        return Length;
    }

    bool SyscallContext::CommitRegisters()
    {
        iovec Vector{ &Registers, sizeof(Registers) };
        return ptrace(PTRACE_SETREGSET, ThreadId, (void *)1, &Vector) == 0;
    }
}
