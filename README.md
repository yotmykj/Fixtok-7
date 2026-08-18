# FixTok

**FixTok** — Android TV приложение, состоящее из одного полноэкранного WebView с TikTok внутри.

```
FixTok → WebView → TikTok
```

## Возможности

- 📺 Полноэкранный WebView с TikTok — без собственного интерфейса поверх
- ✨ Минималистичный splash screen: логотип FixTok + «by Manas» на тёмном фоне
- ⏳ Splash скрывается **после загрузки** TikTok (не по таймеру)
- 🍪 JavaScript, DOM Storage, cookies + third-party cookies, localStorage, сессии
- 🎬 Поддержка HTML5 video и полноэкранного видео
- ⚡ Hardware acceleration
- 🎮 Android TV: D-pad, OK/Select, Back, TV launcher, landscape/fullscreen
- 🔙 Back: если WebView может вернуться — `goBack()`, иначе закрытие Activity
- 🎨 Оригинальная adaptive icon FixTok

## Поведение

```
Запуск FixTok
  → Splash (логотип + «by Manas», анимация появления)
  → загрузка TikTok в WebView
  → splash плавно исчезает
  → остаётся только полноэкранный TikTok
```

## Сборка

Требуется JDK 17+ и Android SDK.

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

Workflow [.github/workflows/build.yml](.github/workflows/build.yml) автоматически собирает
**FixTok-debug.apk** при каждом push и загружает его как Artifact.

## Структура

```
FixTok/
├── .github/workflows/build.yml   # CI-сборка APK
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml   # TV launcher, landscape, permissions
│       ├── java/com/fixtok/tv/MainActivity.kt
│       └── res/                  # layout, splash, adaptive icon, banner
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew                       # Gradle wrapper
```

---

FixTok © Manas
