package com.bedside.clock

/** Newest first. Add an entry here and bump the VERSION file for each release. */
object Changelog {
    val entries: List<Pair<String, List<String>>> = listOf(
        "1.4" to listOf(
            "Updates: Settings → Updates → Check for updates",
            "Automatic updates — installed only in the daytime, never during night hours or within 90 minutes of an alarm",
            "“What's new” message after each update",
            "The clock can reopen itself after an update or restart (switch on “Appear on top” for Bedside Clock)",
            "Clock screen shows a note when an update is available"
        ),
        "1.3.1" to listOf(
            "The colon in the time now sits exactly in the middle of the screen"
        ),
        "1.3" to listOf(
            "The Dim button now switches dimming on and off — it works during night hours too",
            "Dim brightness setting, plus a quick brightness slider: press and hold the Dim button",
            "Choose the dim colour: red, amber or grey",
            "Sunrise and sunset times on the clock screen",
            "Uses the tablet's own location (or a city you type in) for sunrise, sunset and weather",
            "Weather can be switched on or off without losing your location",
            "Version number and this change log in Settings"
        ),
        "1.2" to listOf(
            "Voice commands: tap 🎤 Voice and say “set an alarm for 6 AM”",
            "Also understands repeat days, “in 20 minutes”, cancelling alarms, “when is my alarm” and sleep sounds",
            "Works with “Hey Google, set an alarm…” once Bedside Clock is chosen as the clock app",
            "Speaks its replies out loud"
        ),
        "1.1" to listOf(
            "10 new alarm sounds: sunrise pad, singing bowl, ocean waves, harp, music box, piano, xylophone, church bells, retro game, digital watch",
            "Use any ringtone on the tablet",
            "Use your own music file as an alarm",
            "Scrolling sound list — tap a sound to hear it"
        ),
        "1.0" to listOf(
            "First release: full-screen clock, alarms that ring with the screen off or locked",
            "Repeat days, labels, gentle wake and sunrise glow",
            "Big Snooze button and hold-to-turn-off",
            "Night mode with real screen dimming, sleep sounds, weather"
        )
    )
}
