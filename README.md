# PowerHub — неофіційний Android-клієнт для станцій EcoFlow

Підтримувані моделі: Delta 2, Delta 2 Max, Delta 3, Delta 3 Max, Delta Pro 3.

## Можливості
- Вхід з обліковим записом EcoFlow (той самий, що в офіційному застосунку).
- Моніторинг у реальному часі через MQTT: заряд, вхід і вихід, мережа, сонце, AC, DC, USB, час до заряду або розряду, температура, цикли, SOH.
- Керування: виходи AC, DC і USB, X-Boost, ліміти заряду й розряду, потужність заряду від мережі, резерв, тайм-аути, звук.
- Сповіщення: зникло або з'явилося живлення, низький заряд (поріг налаштовується), повний заряд, станція не на зв'язку.
- Графіки заряду й потужності за 6 год, 24 год, 7 і 30 днів, а також підсумок енергії та часу без мережі.
- Віджет на головний екран і постійне сповіщення зі станом.
- Вкладка «Дані» зі всіма сирими полями від станції, щоб налагоджувати й додавати нові параметри.

## Готовий APK
Кожен push у `main` автоматично збирається через GitHub Actions. Свіжий APK лежить у [Releases → Dev build](../../releases/tag/dev), а теги `v*` стають звичайними релізами.

## Збирання
1. Встановіть [Android Studio](https://developer.android.com/studio) (Ladybug або новішу).
2. File → Open → оберіть теку `PowerHub`. Studio сама завантажить Gradle 8.11 і залежності.
3. Підключіть телефон (Android 8+) з увімкненим налагодженням по USB і натисніть **Run**.
   Або Build → Build APK(s): готовий файл буде в `app/build/outputs/apk/debug/`.

## Перший запуск
1. Увійдіть з email і паролем EcoFlow. Сервер за замовчуванням «Глобальний». Якщо вхід не вдається, спробуйте «Європа».
2. Натисніть «+» і введіть серійний номер станції: він є в офіційному застосунку (Налаштування → Про пристрій) і на наклейці. Оберіть модель.
3. Дозвольте сповіщення. Щоб вони приходили вчасно, вимкніть оптимізацію батареї для PowerHub.

## Як це працює
- `POST https://api.ecoflow.com/auth/login` повертає токен, `GET /iot-auth/app/certification` повертає облікові дані MQTT.
- MQTT `mqtt.ecoflow.com:8883` (TLS):
  - телеметрія: `/app/device/property/{SN}`
  - команди: `/app/{userId}/{SN}/thing/property/set` (+ `set_reply`)
  - повний знімок стану: `/app/{userId}/{SN}/thing/property/get` (+ `get_reply`)
- Delta 2 і Delta 2 Max працюють з JSON. Delta 3 і Delta 3 Max повністю на protobuf. Delta Pro 3 надсилає телеметрію в protobuf, а команди приймає в JSON (`operateType: "TCP"`).

Протокол і proto-схеми взято з відкритого проєкту [tolwi/hassio-ecoflow-cloud](https://github.com/tolwi/hassio-ecoflow-cloud) (Apache-2.0).

## Структура
```
app/src/main/
  proto/                         proto-схеми Delta 3 і Delta Pro 3
  java/app/powerhub/
    api/        EcoflowCloud (REST), MqttLink (Paho MQTT)
    protocol/   моделі, Delta2Protocol, Delta3Protocol, DeltaPro3Protocol, ProtoCodec
    data/       Repository, сховища налаштувань і пароля, HistoryDb (SQLite)
    service/    MonitorService (фоновий моніторинг), AlertEngine (сповіщення)
    widget/     віджет (Glance)
    ui/         екрани на Jetpack Compose
```

Щоб додати нову модель, реалізуйте `DeviceProtocol` і додайте її до `DeviceModel`.

## Обмеження
- API неофіційний: EcoFlow може змінити його без попередження.
- Список станцій не підтягується автоматично, тому серійний номер треба ввести вручну.
- Назви полів для Delta 3 Max і Delta Pro 3 взято зі спільноти. Якщо якийсь показник порожній, подивіться вкладку «Дані».
