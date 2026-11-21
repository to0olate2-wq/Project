package app.falak.player

/**
 * Long-press the app's icon (phones): Live TV, the channel watched last, and Search. Since 8.41
 * they are written in the manifest (res/xml/shortcuts.xml), not made by code as the app starts:
 * 8.40 made them with ShortcutManager at every start, and that version alone got Play Protect's
 * "might be harmful" warning on the owner's phone (likely, not proven: apps that add icons by
 * themselves are a trait of adware). Keep it in the manifest.
 */
object AppShortcuts {
    /** The intent extra the shortcuts carry: "LIVE", "LAST" or "SEARCH" → `vm.openShortcut`. */
    const val EXTRA = "app.falak.player.SHORTCUT"
}
