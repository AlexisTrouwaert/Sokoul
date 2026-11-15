# Playback transport attribution

`PngWrappedMpegTs.kt` and `PngTsDataSource.kt` are adapted from the Movix TV
sources (Movix `app-tv`, package `com.movix.tv.player`).
The original project is licensed under Creative Commons Attribution-NonCommercial
4.0 International; its complete license is retained at `docs/third-party/MOVIX-LICENSE`.

The adapter removes a PNG envelope only when a structurally valid PNG is immediately
followed by three MPEG-TS sync packets. Other bytes and nonzero range reads remain
unchanged. Playback cookies are bounded, remain in memory and are discarded when
another source starts or playback stops.
