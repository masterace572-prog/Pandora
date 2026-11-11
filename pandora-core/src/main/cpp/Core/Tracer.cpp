#include "Tracer.hpp"
#include "FileHooks.hpp"
#include "Structures.hpp"
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <iterator>
#include <dirent.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/select.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <unordered_map>

namespace Pandora
{
    namespace
    {
        std::unordered_map<pid_t, ThreadTraceState> ThreadStates;
        struct ScopedFd
        {
            int Descriptor = -1;
            explicit ScopedFd(int Value) : Descriptor(Value) {}
            ~ScopedFd()
            {
                Reset();
            }
            void Reset(int Value = -1)
            {
                if (Descriptor >= 0)
                    close(Descriptor);
                Descriptor = Value;
            }
        };
        bool ReadRegisters(SyscallContext &Context, pid_t ThreadId)
        {
            Context = {};
            Context.ThreadId = ThreadId;
            Context.Memory.ProcessId = ThreadId;
            iovec Vector{ &Context.Registers, sizeof(Context.Registers) };
            return ptrace(PTRACE_GETREGSET, ThreadId, (void *)1, &Vector) == 0;
        }
        void ContinueThread(pid_t ThreadId, bool AwaitingExit, int Signal = 0)
        {
            errno = 0;
            ptrace(AwaitingExit ? PTRACE_SYSCALL : PTRACE_CONT, ThreadId, nullptr, (void *)(std::intptr_t)Signal);
        }
    }

    int InstallSeccompFilter()
    {
        sock_filter Filter[] = { BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 4),          BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
                                 BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),   BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),
                                 BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 56, 0, 1),  BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE),
                                 BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 437, 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE),
                                 BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 78, 0, 1),  BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE),
                                 BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 80, 0, 1),  BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE),
                                 BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 79, 0, 1),  BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE),
                                 BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 291, 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE),
                                 BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW) };
        sock_fprog Program{ (unsigned short)std::size(Filter), Filter };
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0)
            return -1;
        const auto Result = syscall((long)Arm64Syscall::Seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &Program);
        return Result < 0 ? -1 : 0;
    }
    int GetProcessThreads(pid_t ProcessId, pid_t *Threads, int Capacity, pid_t Exclude)
    {
        char Path[64];
        std::snprintf(Path, sizeof(Path), "/proc/%d/task", ProcessId);
        auto *Directory = opendir(Path);
        if (!Directory)
            return -1;
        int Count = 0;
        while (Count < Capacity)
        {
            auto *Entry = readdir(Directory);
            if (!Entry)
                break;
            if (Entry->d_name[0] == '.')
                continue;
            const auto Thread = std::atoi(Entry->d_name);
            if (Thread > 0 && Thread != Exclude)
                Threads[Count++] = Thread;
        }
        closedir(Directory);
        return Count;
    }
    bool AttachResult::Contains(pid_t ThreadId) const
    {
        for (int Index = 0; Index < Count; ++Index)
            if (Threads[Index] == ThreadId)
                return true;
        return false;
    }
    void AttachResult::DetachAll() const
    {
        for (int Index = 0; Index < Count; ++Index)
            ptrace(PTRACE_DETACH, Threads[Index], nullptr, nullptr);
    }
    AttachResult AttachAllThreads(pid_t ProcessId, pid_t Exclude)
    {
        AttachResult Result;
        for (int Pass = 0; Pass < 5; ++Pass)
        {
            pid_t Threads[ThreadCapacity];
            const int Count = GetProcessThreads(ProcessId, Threads, ThreadCapacity, Exclude);
            if (Count < 0)
                return Result;
            bool Added = false;
            for (int Index = 0; Index < Count; ++Index)
            {
                const auto Thread = Threads[Index];
                if (Result.Contains(Thread))
                    continue;
                if (Result.Count >= (int)ThreadCapacity)
                {
                    Result.DetachAll();
                    return Result;
                }
                if (ptrace(PTRACE_ATTACH, Thread, nullptr, nullptr) < 0)
                {
                    if (errno == ESRCH)
                        continue;
                    Result.DetachAll();
                    return Result;
                }
                int Status = 0;
                if (waitpid(Thread, &Status, WaitAllThreads) < 0 || !WIFSTOPPED(Status) || ptrace(PTRACE_SETOPTIONS, Thread, nullptr, (void *)TraceOptions) < 0)
                {
                    ptrace(PTRACE_DETACH, Thread, nullptr, nullptr);
                    continue;
                }
                // Resume the thread immediately so the app does not sit frozen
                // for the rest of the attach passes. Stops queue up and are
                // drained as soon as the tracer main loop starts running.
                ptrace(PTRACE_CONT, Thread, nullptr, nullptr);
                Added = true;
                Result.Threads[Result.Count++] = Thread;
            }
            if (!Added)
                break;
        }
        Result.Success = Result.Count > 0;
        return Result;
    }
    void TracerMainLoop(pid_t Parent)
    {
        for (;;)
        {
            int Status = 0;
            auto Thread = waitpid(-1, &Status, WaitAllThreads | WaitNoThread);
            if (Thread < 0)
            {
                if (errno == EINTR)
                    continue;
                _exit(0);
            }
            if (WIFEXITED(Status) || WIFSIGNALED(Status))
            {
                ThreadStates.erase(Thread);
                if (Thread == Parent)
                    _exit(0);
                continue;
            }
            if (!WIFSTOPPED(Status))
                continue;
            auto &State = ThreadStates[Thread];
            const auto Event = (unsigned)Status >> 16;
            const auto Signal = WSTOPSIG(Status);
            if (!State.OptionsSet)
            {
                errno = 0;
                if (ptrace(PTRACE_SETOPTIONS, Thread, nullptr, (void *)TraceOptions) == -1)
                {
                    // Never leave a thread stopped: resume it and retry on the
                    // next stop. Skipping the resume deadlocks the whole app.
                    ContinueThread(Thread, false,
                                   (Event == 0 && Signal > 0 && Signal != SIGTRAP) ? Signal : 0);
                    continue;
                }
                State.OptionsSet = true;
                if (Event == 0 && Signal == SIGSTOP)
                {
                    ContinueThread(Thread, State.AwaitingExit);
                    continue;
                }
            }
            if (Event == PTRACE_EVENT_SECCOMP)
            {
                SyscallContext Context{};
                State.AwaitingExit = false;
                if (ReadRegisters(Context, Thread))
                {
                    for (std::size_t Index = 0; Index < State.EntryArguments.size(); ++Index)
                        State.EntryArguments[Index] = Context.Argument(Index);
                    State.AwaitingExit = FileHooks::DispatchEntry(Context) == FileHooks::Action::TraceExit;
                }
                ContinueThread(Thread, State.AwaitingExit);
            }
            else if (Event == 0)
            {
                if (Signal == (SIGTRAP | 0x80))
                {
                    if (State.AwaitingExit)
                    {
                        SyscallContext Context{};
                        if (ReadRegisters(Context, Thread))
                        {
                            Context.EntryArguments = State.EntryArguments;
                            FileHooks::DispatchExit(Context);
                        }
                        State.AwaitingExit = false;
                    }
                    ContinueThread(Thread, false);
                }
                else
                    ContinueThread(Thread, State.AwaitingExit, Signal);
            }
            else
            {
                if (Event == PTRACE_EVENT_EXIT)
                    State.AwaitingExit = false;
                if (Event == PTRACE_EVENT_FORK || Event == PTRACE_EVENT_VFORK || Event == PTRACE_EVENT_CLONE)
                {
                    unsigned long NewThread = 0;
                    if (ptrace(PTRACE_GETEVENTMSG, Thread, nullptr, &NewThread) == 0 && NewThread)
                        ThreadStates.try_emplace((pid_t)NewThread);
                }
                ContinueThread(Thread, State.AwaitingExit);
            }
        }
    }
    void *TracerWatchdog(void *Argument)
    {
        const auto Child = (pid_t)(std::intptr_t)Argument;
        int Status = 0;
        while (waitpid(Child, &Status, 0) < 0 && errno == EINTR)
        {
        }
        // The tracer died or detached. While it is gone, seccomp's RET_TRACE has
        // nowhere to report to and every filtered syscall fails with ENOSYS, so
        // respawn the tracer immediately instead of letting the app crash.
        auto *State = GetGlobalState();
        if (State && State->State.load() == RedirectState::Active)
        {
            SetState(RedirectState::Starting);
            StartTraceAsync();
        }
        return nullptr;
    }
    void *TraceStarterThread(void *)
    {
        const auto Parent = getpid();
        int Pipe[2]{ -1, -1 };
        if (pipe(Pipe) < 0)
        {
            SetState(RedirectState::Failed);
            return nullptr;
        }
        ScopedFd ReadEnd(Pipe[0]), WriteEnd(Pipe[1]);
        if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) < 0)
        {
            SetState(RedirectState::Failed);
            return nullptr;
        }
        const auto Child = fork();
        if (Child < 0)
        {
            SetState(RedirectState::Failed);
            return nullptr;
        }
        if (Child == 0)
        {
            ReadEnd.Reset();
            const auto Result = AttachAllThreads(Parent, getpid());
            const std::uint8_t Success = Result.Count > 0 && Result.Success;
            write(WriteEnd.Descriptor, &Success, 1);
            WriteEnd.Reset();
            if (!Success)
                _exit(1);
            // AttachAllThreads already resumed every thread it seized.
            TracerMainLoop(Parent);
            _exit(0);
        }
        WriteEnd.Reset();
        GetGlobalState()->TracerPid.store(Child);
        fd_set ReadSet;
        FD_ZERO(&ReadSet);
        FD_SET(ReadEnd.Descriptor, &ReadSet);
        timeval Timeout{ 30, 0 };
        std::uint8_t Success = 0;
        if (select(ReadEnd.Descriptor + 1, &ReadSet, nullptr, nullptr, &Timeout) > 0 && read(ReadEnd.Descriptor, &Success, 1) == 1 && Success == 1 && InstallSeccompFilter() >= 0)
        {
            SetState(RedirectState::Active);
            // Watch the tracer and respawn it if it dies, otherwise every
            // filtered syscall in the app would start failing with ENOSYS.
            pthread_t Watchdog;
            if (pthread_create(&Watchdog, nullptr, TracerWatchdog, (void *)(std::intptr_t)Child) == 0)
                pthread_detach(Watchdog);
        }
        else
        {
            kill(Child, SIGKILL);
            waitpid(Child, nullptr, 0);
            SetState(RedirectState::Failed);
        }
        return nullptr;
    }
    int StartTraceAsync()
    {
        pthread_attr_t Attributes;
        if (pthread_attr_init(&Attributes) == 0)
        {
            pthread_attr_setdetachstate(&Attributes, PTHREAD_CREATE_DETACHED);
            pthread_t Thread;
            const int Result = pthread_create(&Thread, &Attributes, TraceStarterThread, nullptr);
            pthread_attr_destroy(&Attributes);
            if (Result == 0)
                return 0;
        }
        SetState(RedirectState::Failed);
        return -1;
    }
}
