<div align="center">
    <h1>📖 InkRead</h1>
    <p>An E-Ink optimized Android RSS reader, forked from <a href="https://github.com/ReadYouApp/ReadYou">ReadYou</a>.</p>
    <p>Designed for <strong>Boox Palma Pro 2</strong> and other E-Ink Android devices.</p>
</div>

<br>

<div align="center">
    <img alt="License" src="https://img.shields.io/github/license/ivanlee1999/inkread?color=333&style=flat-square">
    <a target="_blank" href="https://github.com/ivanlee1999/inkread/releases">
        <img alt="Version" src="https://img.shields.io/github/v/release/ivanlee1999/inkread?color=333&label=version&style=flat-square">
    </a>
    <img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/ivanlee1999/inkread?color=333&style=flat-square">
</div>

<br>

## What is InkRead?

InkRead is a fork of [ReadYou](https://github.com/ReadYouApp/ReadYou) with an **E-Ink optimized mode** for a book-like reading experience on E-Ink Android devices.

It keeps all of ReadYou's features (Material You design, FreshRSS/Fever/Google Reader API support, local RSS) and adds an E-Ink mode you can toggle on/off.

## E-Ink Features

### 🖤 Pure B&W Theme
- Pure white (#FFFFFF) background, pure black (#000000) text
- No animations, gradients, or gray tones
- High contrast for E-Ink clarity at 300 PPI

### 📖 KOReader-Style Pagination
- **Article content**: WebView with CSS multi-column layout — text flows page-by-page like a book
- **Article list**: Dynamic items-per-page based on screen height — no scrolling, just page turns
- **Tap zones**: Left 40% = previous page, right 60% = next page
- **Volume buttons**: Volume Up/Down for page navigation (with debounce for E-Ink refresh)
- No content overlap or gaps between pages
- Images never split across pages

### 🔤 Downloadable Fonts
Fonts download on first use — not bundled in the APK.

**English:**
- System Serif (default)
- Source Serif 4
- Literata
- Merriweather
- Lora
- EB Garamond

**Chinese (中文):**
- System (default)
- Noto Serif SC (思源宋体)
- LXGW WenKai (霞鹜文楷)
- Source Han Serif SC (思源宋体)

### ⚙️ E-Ink Settings
- E-Ink Mode toggle (doesn't break normal ReadYou experience)
- Font size: 14sp – 36sp
- Separate English and Chinese font selection
- Auto-detection of Boox devices

## Screenshots

*Coming soon — testing on Boox Palma Pro 2*

## Download

Download the latest APK from [Releases](https://github.com/ivanlee1999/inkread/releases).

Sideload on your E-Ink device:
1. Download the `.apk` file directly on your Boox browser
2. Or transfer via USB/cloud and install

## FreshRSS / Google Reader API

InkRead supports the same sync backends as ReadYou:
- **FreshRSS** (Google Reader API)
- **Fever API**
- **Google Reader API** compatible services
- Local RSS (no sync)

## Building

```bash
# Requires JDK 17
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/github/debug/`

## Credits & Acknowledgments

InkRead is a fork of [**ReadYou**](https://github.com/ReadYouApp/ReadYou) by [Ash](https://github.com/AshlyneS) and the ReadYou contributors. This project would not exist without their excellent work.

### ReadYou's Acknowledgments

ReadYou is built on the shoulders of many open-source projects:

- [MusicFree](https://github.com/AshlyneS/MusicFree)
- [Jemore](https://github.com/nicegrief/Jemore)
- [Twidere X](https://github.com/nicegrief/TwidereX-Android)
- [MusicYou](https://github.com/Kyant0/MusicYou)
- [ParseRSS](https://github.com/muhrifqii/ParseRSS)
- [Readability4J](https://github.com/dankito/Readability4J)
- [opml-parser](https://github.com/mdewilde/opml-parser)
- [compose-html](https://github.com/ireward/compose-html)
- [Rome](https://github.com/rometools/rome)
- [Feeder](https://gitlab.com/spacecowboy/Feeder)
- [Seal](https://github.com/JunkFood02/Seal)
- [news-flash](https://gitlab.com/news-flash)
- [besticon](https://github.com/mat/besticon)
- [Jiffy Reader](https://github.com/ansh/jiffyreader.com)

Special thanks to **@Kyant0** for design inspiration and the Monet engine implementation for ReadYou.

## License

GNU GPL v3.0 © [ReadYou](https://github.com/ReadYouApp/ReadYou/blob/main/LICENSE)

This project is licensed under the same [GNU General Public License v3.0](LICENSE) as ReadYou, in compliance with the original license terms. All modifications are released under the same license.
