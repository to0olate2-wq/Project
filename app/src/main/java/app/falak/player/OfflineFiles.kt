package app.falak.player

import androidx.core.content.FileProvider

/** Hands a downloaded film or episode to another video player (its own name, so it never clashes with others). */
class OfflineFiles : FileProvider()
