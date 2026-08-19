package dev.montb.basickeyboard.ime

/** A small curated emoji set for the basic picker (offline, no fonts bundled, uses
 *  the system emoji font). Grouped loosely; expand freely. */
object EmojiData {
    val emoji: List<String> = (
        "😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 " +
        "🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 " +
        "🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 " +
        "👍 👎 👌 ✌️ 🤞 🤟 🤘 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤙 💪 🙏 👏 🙌 👐 ✍️ " +
        "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 🔥 ⭐ 🌟 ✨ ⚡ ☀️ 🌈 " +
        "🎉 🎊 🎁 🎈 🎂 🍕 🍔 🍟 🌮 🍜 🍣 🍦 🍩 🍪 ☕ 🍺 🍷 🚗 ✈️ 🏠 📱 💻 ⏰ 📅 ✅"
        ).split(" ").filter { it.isNotBlank() }
}
