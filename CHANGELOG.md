# Changelog

## 0.7.0b3

## 🇷🇺 Русский

### Добавлено

- Выбор языка браслета в разделе «Экран»: 69 языковых вариантов и режим «Как в приложении».
- Отдельная карточка сна во вкладке «Активность» с разделением на ночной и дневной сон.
- Подробный просмотр сна: история по дням, отдельные эпизоды, время начала и окончания, график стадий и периоды бодрствования — при наличии данных браслета.
- Сохранение эпизодов сна и языка браслета в резервной копии.

### Исправлено

- Браслет больше не переключается принудительно на русский при сопряжении и синхронизации времени.
- Сон за день учитывает несколько эпизодов, а не только последний. Повторная синхронизация не дублирует записи.
- Дата перенесена под «Сегодня» на главной. Убраны лишние верхние заголовки во вкладках «Активность» и «Браслет».
- Выровнены размеры и отступы верхних карточек на основных вкладках.

## 🇬🇧 English

### Added

- Band language selection in Display settings: 69 language variants and a “Same as app” option.
- A dedicated sleep card in the Activity tab, showing night and daytime sleep separately.
- Detailed sleep view: daily history, individual episodes, start and end times, a sleep-stage chart and awake periods, when provided by the band.
- Sleep episodes and band language are now included in backups.

### Fixed

- The band no longer switches to Russian automatically during pairing or time synchronisation.
- Daily sleep totals now include multiple episodes instead of just the latest one. Repeated synchronisation does not duplicate records.
- The date now appears below “Today” on the Home tab. Removed redundant headings from the Activity and Band tabs.
- Aligned the sizes and spacing of the top cards across the main tabs.

## 0.7.0b2

### Fixed

- Custom watch-face BINs from older R390 Studio versions are prepared with
  the correct variant count before installation.
- BIN validation now rejects inconsistent variants before transmission.

Previously installed affected watch faces need to be installed again or
replaced with files created by a corrected editor.

## 0.7.0b1

### Added

- Author profile link and a support button in About.

### Changed

- Removed the obsolete BIN installer from debug mode. Custom watch faces
  remain available through Band → Installed.
