<div align="center">
  <img src="assets/banners/gh-banner.png" width="auto" height="auto" alt="LibreTube">

[![GPL-v3](assets/widgets/license-widget.svg)](https://www.gnu.org/licenses/gpl-3.0.en.html)

[<img src="assets/badges/ghload.png" alt="Get it on GitHub" width="30%">](https://github.com/rachadaccoumeh/LibreTube/releases/latest)

</div>

## LibreTube Fork — actively maintained, with a modern YouTube-like UI

This is an actively maintained fork of [LibreTube](https://github.com/libre-tube/LibreTube). It follows upstream
regularly and adds a player and app experience that feels like the current YouTube app, faster playback and
reliable downloads.

The app installs as **LibreTube Fork** (`com.github.libretube.fork`), next to the official LibreTube, so you can
keep both.

### What is new in this fork

**Player**
- YouTube-style controls: round play, previous and next buttons, time pill and fullscreen button at the bottom edge
- Thin progress line on the video edge: white while playing, red with a dot when the controls show; drag it to seek
- Swipe down on the video to minimize, pull down on the title area for portrait fullscreen, swipe down to leave fullscreen
- Smooth in-place fullscreen (no black flash), brightness and volume swipes in fullscreen, free pinch zoom and pan
- The paused video shows its frame when the app opens, instead of a black screen

**Floating mini player**
- A small video card that stays on top while you browse the app
- Drag it anywhere, pinch to resize it, or park it on the left or right side of the screen
- Control bar with play/pause and 10-second rewind and forward

**Playback**
- SABR (YouTube's own streaming protocol) first, with DASH and HLS as fallbacks
- Auto quality that really adapts to your connection

**Downloads**
- Up to 6 parallel downloads, with pause, resume, stop and "resume all"
- Verify downloads and a full backup and restore that includes the media files
- Fixes for downloads that were lost or corrupted in upstream (second quality, resumed SABR downloads)

**Feed and lists**
- NEW badges in the subscriptions feed and a "mark as caught up" divider
- 3-dot menu on every video card, a back-to-top button on long lists
- Random suggestions under downloaded videos

### Install

Download the APK from the [latest release](https://github.com/rachadaccoumeh/LibreTube/releases/latest) and open it on your phone.

### Contributing

Issues and pull requests are welcome. Please report bugs of this fork in this repository's
[issues](https://github.com/rachadaccoumeh/LibreTube/issues), not in the upstream project.

---

The upstream LibreTube README follows. All credit for the base app goes to the LibreTube team and contributors.

<details>
  <summary>📜️ Credits</summary>

<sub>Readme Design and Banners by [XelXen](https://github.com/XelXen)</sub> <br>
<sub>Readme Screenshots by [ARBoyGo](https://github.com/ARBoyGo)</sub> <br>
<sub>Readme Emoji is from [openmoji](https://openmoji.org)</sub>

  <summary>Icons</summary>

<sub>[Default App Icon](https://github.com/libre-tube/LibreTube/blob/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png) by [XelXen](https://github.com/XelXen)</sub> <br>
<sub>[Boosted Bird](https://github.com/libre-tube/LibreTube/blob/master/app/src/main/res/mipmap-xxxhdpi/ic_bird_round.png) by [Margot Albert-Heuzey](https://margotdesign.ovh)</sub>

</details>

<h2 align="left">
<sub>
<img  src="assets/readme/about.svg"
      height="30"
      width="30">
</sub>
About
</h2>

YouTube has an extremely invasive [privacy policy](https://support.google.com/youtube/answer/10364219) which relies on using user data in unethical ways. They store a lot of your personal data - ranging from ideas, music taste, content, political opinions, and much more than you think.

LibreTube aims at improving the users' privacy by being independent from Google and bypassing their data collection as much as possible. The app only sends the minimum amount of data necessary to ensure that the app works, e.g. it only loads the YouTube-video you want to play without tracking your behavior when using the app.

If you have questions or need help, please make sure to read the [FAQ](https://libre-tube.github.io/#faq) before asking for help at the community channels. The [Matrix room](https://matrix.to/#/#LibreTube:matrix.org) is considered as the main communication channel, all other forums or social media accounts are maintained by volunteers from the community but not the developer(s).

<h2 align="left">
<sub>
<img  src="assets/readme/phone.svg"
      height="30"
      width="30">
</sub>
Screenshots
</h2>

<div style="width:100%; display:flex; justify-content:space-between;">

[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_1.jpg" width=19% alt="Home">](fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_1.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_2.jpg" width=19% alt="Home">](fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_2.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_3.jpg" width=19% alt="Subscriptions">](fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_3.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_4.jpg" width=19% alt="Library">](fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_4.jpg)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_9.jpg" width=19% alt="Channel Overview">](fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_9.jpg)

* More screenshots can be found [here](https://github.com/libre-tube/LibreTube/blob/master/SCREEN_SHOT.md)

</div>

<h2 align="left">
<sub>
<img  src="assets/readme/feature.svg"
      height="30"
      width="30">
</sub>
Features
</h2>

- [x] No Ads or Tracking
- [x] Subscriptions
- [x] Subscription Groups
- [x] User Playlists
- [x] Playlist Bookmarks
- [x] Watch/Search History
- [x] Downloads
- [x] Background playback
- [x] User Accounts via [Piped](https://github.com/TeamPiped/Piped) (optional)
- [x] [SponsorBlock](https://sponsor.ajay.app/) - skip sponsored segments in videos
- [x] [ReturnYouTubeDislike](https://www.returnyoutubedislike.com/) - provides video dislike statistics
- [x] [DeArrow](https://dearrow.ajay.app/) - provides better titles and thumbnails

<h2 align="left">
<sub>
<img  src="assets/readme/community.svg"
      height="30"
      width="30">
</sub>
Contributing
</h2>

For information on how to contribute, please check the [contribution guide](./CONTRIBUTING.md).

<h2 align="left">
<sub>
<img  src="assets/readme/donate.svg"
      height="30"
      width="30">
</sub>
Donate
</h2>

Donations can be made at:
* <https://github.com/sponsors/Bnyro>
* <https://liberapay.com/Bnyro>
* Monero (XMR): 47jAx7jMFo5iqy9VgDH98qL1bSK4kr6Pxi7HKWcRwsxbVYJdjxJtyrwXeAUa5MutvcQUmWMBfvAKnPAutDHvWEymUgLm5v8
* Ethereum (ETH): 0x599909f54CdC18B997Be8F032341d1Fb14BF4F39

Contributions in any form are welcome!

<h2 align="left">
📝 Translations
</h2>

<a href="https://hosted.weblate.org/projects/libretube/#languages">
<img src="https://hosted.weblate.org/widgets/libretube/-/287x66-grey.png" alt="Translation status" />
</a>

<h2 align="left">
<sub>
<img  src="assets/readme/ltvnp.svg"
      height="30"
      width="30">
</sub>
Differences to NewPipe
</h2>

LibreTube's main difference to NewPipe is that it has a much stronger focus on user experience. LibreTube uses the modern [Material 3 Expressive](https://m3.material.io) design, supports external APIs such as SponsorBlock, ReturnYouTubeDislike, or DeArrow, and allows you to synchronize your user data across devices, e.g. via Piped. <!-- TODO: replace with LibreTube sync server -->

While LibreTube only supports content from YouTube, NewPipe also allows the use of other platforms like SoundCloud, PeerTube, Bandcamp and media.ccc.de.

Both, LibreTube and NewPipe, are great clients for watching YouTube videos. There's no general answer about which one is better, just try them both and see which one fits you best.

<h2 align="left">
<sub>
<img  src="assets/readme/privacy.svg"
      height="30"
      width="30">
</sub>
Privacy Policy and Disclaimer
</h2>


LibreTube aims to protect the privacy of its users. [Our Privacy Policy](/PRIVACY_POLICY.md) gives detailed information on which data the app stores in order to work, how it is being used, and how the project protects your personal information. It is recommended to read the privacy policy of LibreTube as well as the privacy policy of the instance you have chosen inside the app.

## License
[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

LibreTube is [Free Software](https://en.wikipedia.org/wiki/Free_software): You can use, study, share and modify it at your will. The app can be redistributed and/or modified under the terms of the
[GNU General Public License version 3 or later](https://www.gnu.org/licenses/gpl.html) published by the
[Free Software Foundation](https://www.fsf.org/).

<div align="right">
<table><td>
<a href="#start-of-content">↥ Scroll to top</a>
</td></table>
</div>
