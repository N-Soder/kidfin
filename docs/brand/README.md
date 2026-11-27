# Brand

Logo: wordmark "Kidfin" in Fraunces (OFL) with a purple jellyfish as the dot on the i (option C,
chosen 2026-11-27). Colours and type follow the "Storybook" night palette;
see `app/src/main/java/au/kidfin/tv/ui/Theme.kt`.

- Launcher icon: `app/src/main/res/drawable/icon.xml` (vector).
- TV banner: `app/src/main/res/drawable-*/banner.png` (mdpi to xxhdpi), rendered from `banner.html`:

```sh
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
  --allow-file-access-from-files --window-size=640,360 --force-device-scale-factor=1.5 \
  --screenshot=app/src/main/res/drawable-xxhdpi/banner.png "file://$PWD/docs/brand/banner.html"
R=app/src/main/res
sips -z 360 640 $R/drawable-xxhdpi/banner.png --out $R/drawable-xhdpi/banner.png
sips -z 270 480 $R/drawable-xxhdpi/banner.png --out $R/drawable-hdpi/banner.png
sips -z 180 320 $R/drawable-xxhdpi/banner.png --out $R/drawable-mdpi/banner.png
```

Fonts: Fraunces and Outfit variable TTFs from github.com/google/fonts (OFL 1.1); licences ship in
the APK under `assets/licences/`.

README header (`docs/screenshots/hero.jpg`, made-up titles, no third-party art):

```sh
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
  --allow-file-access-from-files --window-size=2560,880 \
  --screenshot=hero.png "file://$PWD/docs/brand/readme-hero.html"
sips -s format jpeg -s formatOptions 88 hero.png --out docs/screenshots/hero.jpg && rm hero.png
```
