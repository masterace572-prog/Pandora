#include "Structures.hpp"
#include <cstring>
#include <new>
#include <sys/mman.h>

namespace Pandora
{
    namespace
    {
        std::atomic<GlobalState *> SharedState{ nullptr };
    }
    GlobalState *GetGlobalState()
    {
        return SharedState.load(std::memory_order_acquire);
    }
    std::uint64_t HashPath(const char *Path, std::size_t Length)
    {
        std::uint64_t Hash = FnvOffset;
        for (std::size_t Index = 0; Index < Length; ++Index)
            Hash = (Hash ^ (unsigned char)Path[Index]) * FnvPrime;
        return Hash;
    }
    bool EnsureGlobalState()
    {
        if (GetGlobalState())
            return true;
        void *Region = mmap(nullptr, sizeof(GlobalState), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
        if (Region == MAP_FAILED)
            return false;
        std::memset(Region, 0, sizeof(GlobalState));
        auto *State = new (Region) GlobalState;
        for (auto &Rule : State->Rules)
            Rule.Enabled = false;
        State->RuleCount.store(0);
        State->Initialized.store(false);
        State->State.store(RedirectState::Idle);
        State->TracerPid.store(0);
        if (!State->Initialized.load())
        {
            std::memset(State->OriginalIndex, 0xff, sizeof(State->OriginalIndex));
            std::memset(State->ReplacementIndex, 0xff, sizeof(State->ReplacementIndex));
            State->Initialized.store(true);
        }
        SharedState.store(State, std::memory_order_release);
        return true;
    }
    void SetState(RedirectState State)
    {
        if (auto *Shared = GetGlobalState())
            Shared->State.store(State);
    }
    bool GlobalState::AddRule(const char *Original, const char *Replacement)
    {
        const auto Index = RuleCount.load();
        if (Index >= RuleCapacity)
            return false;
        const auto OriginalLength = strnlen(Original, PathCapacity);
        const auto ReplacementLength = strnlen(Replacement, PathCapacity);
        if (OriginalLength == 0 || OriginalLength == PathCapacity || ReplacementLength == 0 || ReplacementLength == PathCapacity)
            return false;
        auto &Rule = Rules[Index];
        std::memcpy(Rule.OriginalPath, Original, OriginalLength + 1);
        std::memcpy(Rule.ReplacementPath, Replacement, ReplacementLength + 1);
        Rule.OriginalLength = OriginalLength;
        Rule.ReplacementLength = ReplacementLength;
        Rule.OriginalHash = HashPath(Rule.OriginalPath, Rule.OriginalLength);
        Rule.ReplacementHash = HashPath(Rule.ReplacementPath, Rule.ReplacementLength);
        Rule.Enabled = true;
        auto Insert = [Index](std::int32_t *Table, std::uint64_t Hash)
        {
            auto Slot = (std::uint8_t)Hash;
            while (Table[Slot] != -1)
                ++Slot;
            Table[Slot] = (std::int32_t)Index;
        };
        Insert(OriginalIndex, Rule.OriginalHash);
        Insert(ReplacementIndex, Rule.ReplacementHash);
        RuleCount.fetch_add(1);
        return true;
    }
    const RedirectRule *GlobalState::FindRule(const char *Path, std::size_t Length, bool Reverse) const
    {
        const auto Hash = HashPath(Path, Length);
        const auto First = (std::uint8_t)Hash;
        auto Slot = First;
        const auto *Table = Reverse ? ReplacementIndex : OriginalIndex;
        do
        {
            if (Table[Slot] == -1)
                return nullptr;
            const auto &Rule = Rules[Table[Slot]];
            const auto *Candidate = Reverse ? Rule.ReplacementPath : Rule.OriginalPath;
            if ((Reverse ? Rule.ReplacementHash : Rule.OriginalHash) == Hash && (Reverse ? Rule.ReplacementLength : Rule.OriginalLength) == Length && std::memcmp(Candidate, Path, Length) == 0)
                return &Rule;
            ++Slot;
        } while (Slot != First);
        return nullptr;
    }
}
