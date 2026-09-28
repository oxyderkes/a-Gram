#ifndef AGRAM_NATIVE_OWNER_POLICY_H
#define AGRAM_NATIVE_OWNER_POLICY_H

#include <string>

namespace AgramNativeOwnerPolicy {
constexpr unsigned int ExtensionTag = 0x41474f57; // AGOW, trailing v5 extension
constexpr unsigned int ExtensionVersion = 1;
enum class StartupAction { Quarantine, Keep, BindLegacyActive, RetireAndBind };

inline bool validOwner(const std::string &owner) {
    if (owner.size() != 36) return false;
    for (unsigned int i = 0; i < owner.size(); ++i) {
        const char value = owner[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (value != '-') return false;
        } else if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
            return false;
        }
    }
    return true;
}

inline StartupAction startup(const std::string &expected, const std::string &stored,
                             bool hasOwnerExtension, bool activeUser, bool validConfig) {
    if (!validConfig || !validOwner(expected)) return StartupAction::Quarantine;
    if (hasOwnerExtension && stored == expected) return StartupAction::Keep;
    if (activeUser) {
        return hasOwnerExtension ? StartupAction::Quarantine : StartupAction::BindLegacyActive;
    }
    return StartupAction::RetireAndBind;
}
}
#endif
