"""Source contracts for the compact menu; Android compilation is checked separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "android/TMessagesProj/src/main/java/org/telegram/ui/AgramContainerSetupActivity.java").read_text(encoding="utf-8")


def method(start, end):
    return SOURCE.split(start, 1)[1].split(end, 1)[0]


class ContainerMenuTest(unittest.TestCase):
    def test_no_duplicate_identity_card(self):
        self.assertNotIn("identityCard", SOURCE)
        self.assertNotIn("Выбор контейнера не требуется и недоступен", SOURCE)

    def test_archive_switch_uses_shared_account_preference(self):
        archive = method("private void addArchiveCard(", "private boolean sameContainer(")
        self.assertIn("keepDeletedSwitch = settingSwitch(", archive)
        self.assertIn("MessagesController.getInstance(account).isKeepDeletedMessagesEnabled()", archive)
        self.assertNotIn("setKeepDeletedMessagesEnabled(", archive)

    def test_save_snapshots_and_fences_archive_preference(self):
        save = method("private void saveAndContinue()", "private void finishSuccessfulSave()")
        self.assertIn("if (saving || !sameContainer()) return;", save)
        self.assertLess(save.index("final boolean keepDeleted = keepDeletedSwitch.isChecked();"),
                        save.index("settingsQueue.postRunnable"))
        self.assertIn("setKeepDeletedMessagesEnabled(\n                        keepDeleted, containerId, sessionGeneration)", save)
        self.assertIn('throw new IllegalStateException("Deleted message preference could not be persisted")', save)

    def test_details_start_collapsed_and_do_not_change_settings(self):
        details = method("private void addExpandableDetails(", "private EditText profileInput(")
        self.assertIn("body.setVisibility(View.GONE)", details)
        self.assertIn("expanded ? View.GONE : View.VISIBLE", details)
        self.assertNotIn("markChanged()", details)
        self.assertNotIn("saveAndContinue()", details)
        self.assertIn("ThemeDescription.FLAG_SELECTOR", details)
        self.assertIn("Theme.key_windowBackgroundWhiteGrayText",
                      method("private void addDetails(", "private void addExpandableDetails("))

    def test_privacy_limitations_remain_in_details(self):
        network = method("private void addNetworkCard(", "private void addPushCard(")
        push = method("private void addPushCard(", "private void addGhostCard(")
        archive = method("private void addArchiveCard(", "private boolean sameContainer(")
        for section in (network, push, archive):
            self.assertIn("addDetails(context, card,", section)
        self.assertIn("могут подключаться напрямую", network)
        self.assertIn("Сервер видит IP подключения, но не текст сообщений", push)
        self.assertIn("не зашифрован отдельным ключом PIN", archive)
        self.assertIn("Очистка кэша", archive)

    def test_contacts_still_explicit_and_scoped(self):
        contacts = method("private void addContactsCard(", "private static String stateLabel(")
        self.assertIn("UserConfig.getInstance(account).isContactSyncAllowed()", contacts)
        self.assertIn("Передавать имена и номера из телефона в Telegram", contacts)
        self.assertIn("не удаляет ранее загруженные контакты", contacts)

    def test_exact_profile_preview_retained(self):
        profile = method("private void addProfileCard(", "private void addNetworkCard(")
        self.assertIn('addExpandableDetails(context, card, "Данные для Telegram", preview)', profile)
        preview = method("private void updatePreview()", "private void applyLockedProfileState()")
        for field in ("device_model:", "system_version:", "api_id:", "app_version:", "official_app:"):
            self.assertIn(field, preview)

    def test_new_toggle_uses_theme_and_dirty_tracking(self):
        toggle = method("private ToggleCell settingSwitch(Context context, String title, String subtitle, boolean checked)",
                        "private static final class ChoiceCell")
        self.assertIn("markChanged()", toggle)
        self.assertIn("Theme.key_switchTrackChecked", toggle)
        self.assertIn("Theme.key_windowBackgroundWhiteBlackText", toggle)


if __name__ == "__main__":
    unittest.main()
