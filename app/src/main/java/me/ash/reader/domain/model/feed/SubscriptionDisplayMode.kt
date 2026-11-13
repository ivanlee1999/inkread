package me.ash.reader.domain.model.feed

/** Null storage preserves the pre-existing full-content/browser presets without a new fetch. */
enum class SubscriptionDisplayMode(val value: String) {
    LOCAL("local"), SERVER("server"), ORIGINAL("original");

    companion object {
        fun fromStored(value: String?): SubscriptionDisplayMode? = entries.firstOrNull { it.value == value }
    }
}
