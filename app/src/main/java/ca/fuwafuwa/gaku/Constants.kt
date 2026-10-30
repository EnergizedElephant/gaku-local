@file:JvmName("Constants")

package ca.fuwafuwa.gaku

// Thanks to the fact that SqliteOpenHelper.onUpgrade() doesn't work (due to multi-threading and getDao() being called before onUpgrade()),
// we version/upgrade the DBs by changing the name. Lol. Should probably fix this if this becomes an issue in the future.
const val JMDICT_DATABASE_NAME = "DB_KakuDict-02-16-2019.db"
const val SCREENSHOT_FOLDER_NAME = "screenshots" // only purged at startup, never written

const val DB_SPLIT_CHAR = "\ufffc"
const val DB_JMDICT_NAME = "JMDICT"
const val DB_KANJIDICT_NAME = "KANJIDICT"
const val DB_ENAMEDICT_NAME = "ENAMEDICT"

const val GAKU_PREF_FILE = "ca.fuwafuwa.gaku"
const val GAKU_PREF_SHOW_HIDE = "ShowHide"
const val GAKU_PREF_INSTANT_MODE = "InstantMode"
const val GAKU_PREF_FIRST_LAUNCH = "FirstLaunch"
// Android 13+ notification permission: asked once, since Gaku works without it
const val GAKU_PREF_NOTIFICATIONS_ASKED = "NotificationsAsked"
// Where the capture box was last left, in screen pixels
const val GAKU_PREF_BOX_X = "BoxX"
const val GAKU_PREF_BOX_Y = "BoxY"
const val GAKU_PREF_BOX_WIDTH = "BoxWidth"
const val GAKU_PREF_BOX_HEIGHT = "BoxHeight"

const val EXTRA_PROJECTION_RESULT_CODE = "ca.fuwafuwa.gaku.PROJECTION_RESULT_CODE"
const val EXTRA_PROJECTION_RESULT_INTENT = "ca.fuwafuwa.gaku.PROJECTION_RESULT_INTENT"

const val WINDOW_CAPTURE = "WINDOW_CAPTURE"
const val WINDOW_INFO = "WINDOW_INFO"
const val WINDOW_EDIT = "WINDOW_EDIT"
const val WINDOW_INSTANT_KANJI = "WINDOW_INSTANT_KANJI"
const val WINDOW_KANJI_CHOICE = "WINDOW_KANJI_CHOICE"
const val WINDOW_HISTORY = "WINDOW_HISTORY"

const val GAKU_CHANNEL_ID = "gaku_notification_channel_id"
const val GAKU_CHANNEL_NAME = "Show Gaku Notification"

const val REQUEST_SCREENSHOT = 100
const val REQUEST_DRAW_ON_TOP = 200
const val REQUEST_SERVICE_TOGGLE_INSTANT_MODE = 500
const val REQUEST_SERVICE_SHUTDOWN = 600
const val REQUEST_SERVICE_TOGGLE_SHOW_HIDE = 700
const val REQUEST_NOTIFICATIONS = 800



