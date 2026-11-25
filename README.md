# ТРАЧУ.

**Личные финансы под контролем.** Offline-first Android-приложение от Moorkis Development.

ТРАЧУ. помогает вести счета, доходы, расходы, переводы, бюджеты, долги, цели и регулярные платежи. Данные хранятся на устройстве. Регистрация, банковские подключения, рекламные SDK и аналитические трекеры не используются.

> Проект находится в активной разработке. Описание отражает реализованные и запланированные возможности; актуальная матрица готовности ведётся в [документации](docs/RELEASE_CHECKLIST.md).

## Технологии

- Kotlin, Jetpack Compose, Material 3
- MVVM, Room, DataStore, WorkManager
- Android Keystore, BiometricPrompt, Jetpack Glance
- minSdk 26 (Android 8.0), targetSdk 36

## Приватность

- Финансовые данные остаются в локальной базе Room.
- Сетевой запрос выполняется только по явному действию пользователя для получения валютных курсов.
- Резервная копия шифруется паролем, выбранным пользователем, и сохраняется через системный выбор файла.
- Приложение не подключается к банкам и не передаёт финансовую историю на сервер.

Подробности: [политика конфиденциальности](docs/PRIVACY_POLICY.md), [условия использования](docs/TERMS.md).

## Сборка

Открой проект в Android Studio или используй установленный Gradle:

```powershell
gradle --no-daemon assembleDebug
gradle --no-daemon testDebugUnitTest
gradle --no-daemon lintDebug
```

Первая полноценная сборка и проверки выполняются GitHub Actions после интеграции основной функциональности.

## Документация

- [Аудит оригинала и конкурентов](docs/PRODUCT_AUDIT.md)
- [Архитектура и финансовые инварианты](docs/ARCHITECTURE.md)
- [План релиза](docs/RELEASE_CHECKLIST.md)
- [Политика конфиденциальности](docs/PRIVACY_POLICY.md)
- [Условия использования](docs/TERMS.md)
- [Информация о разработчике](docs/DEVELOPER.md)

## Разработчик

**Moorkis Development**  
GitHub: https://github.com/epbloger/trachu

© 2026 Moorkis Development. Все права защищены. См. [LICENSE](LICENSE).
