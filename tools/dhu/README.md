# Desktop Head Unit profiles

Head-unit setups for checking the car UI on Google's Desktop Head Unit (DHU):

```sh
python tools/dhu_driver.py tools/dhu/p480_6in.ini
```

| Profile | DHU video | dpi | Margins | Boophis area | Stands in for |
|---|---|---|---|---|---|
| `p720_8in` | 1280×720 | 200 | none | ~950×560 dp, rail left | 8–9" 16:9 OEM screens |
| `p480_7in` | 800×480 | 160 | none | 800×395 dp, rail bottom | cheap 6.5–7" aftermarket units |
| `p480_6in` | 800×480 | 160 | 50 w, 30 h | ~737×360 dp, rail bottom | 6" units; the shortest screen here |
| `p720_wide` | 1280×720 | 160 | 220 h | ~770×480 dp split view beside Maps | very wide panels (about 1920×720) |
| `p1080_123` | 1920×1080 | 240 | 140 h | ~773×627 dp split view | 12.3" widescreens at 1080p |
| `p1080_ultrawide` | 1920×1080 | 240 | 360 h | ~768×464 dp split view | 1920×720 panels served at 1080p |
| `p1080_portrait` | 1080×1920 | 240 | none | ~720×1190 dp, rail bottom | tall portrait screens (Ford SYNC 4, Ram 12", Subaru 11.6") |
| `p720_insets` | 1280×720 | 200 | insets 60 bottom, 120 right | AA keeps the app inside the insets | units whose bezel or overlay covers part of the screen |
| `p1440` | 2560×1440 | 280 | none | can't be tested here | high-density units |
| `p1440_equiv` | 1920×1080 | 210 | none | same dp as `p1440` (about 1463×823 dp) | 1440p layout, at 1080p sharpness |

The dpi and margins for specific cars are estimates; only the 800×480, 1280×720 and 1080p tiers
and the margin keys are in Google's DHU documentation
(<https://developer.android.com/training/cars/testing/dhu>).

## Quirks of DHU 2.0 (build 2022-03-30)

- The profiles carry no comments, since nobody has checked whether this DHU's parser accepts them.
- `p1440` connects and then disconnects: Android Auto on the phone refuses 1440p over H.264
  ("VideoCodecResolutionType 4 is not allowed for the codec type 3"), and H.264 is all this DHU
  offers. `p1440_equiv` checks the same layout: Boophis sizes everything in dp, and 1920×1080 at
  210 dpi gives the same dp area as 2560×1440 at 280 dpi.
- In portrait, console `tap x y` lands at 4/3 of the given coordinates. To hit (640, 1860), send
  `tap 480 1395`.
- At 1280×720 and wider, Android Auto shows apps in a split view beside Maps rather than full width.
