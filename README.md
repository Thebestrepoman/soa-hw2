# Marketplace — домашнее задание №2

Сервис реализует все 10 пунктов задания: OpenAPI, генерацию API/DTO, CRUD, PostgreSQL/Flyway, контрактные ошибки и валидацию, транзакционные заказы, JSON-логи, JWT и роли.

## Быстрый запуск

Требуются Docker с Compose и Python 3 для демонстрации. Локальная Java для Docker-сборки не нужна.

```bash
cp .env.example .env
docker compose up --build -d --wait
docker compose logs app
python3 scripts/demo.py
```

API: `http://localhost:8080`. Контракт: `http://localhost:8080/openapi.yaml` (можно импортировать в Swagger Editor / Postman). Данные PostgreSQL сохраняются в named volume. `docker compose down` останавливает сервисы и сохраняет данные; `docker compose down -v` удаляет данные.

`.env.example` содержит демонстрационные секреты. Для своего окружения замените `JWT_SECRET` (`openssl rand -hex 32`) и `ADMIN_PASSWORD`. `.env` не попадает в Git и Docker image. Порты публикуются только на localhost; можно изменить `APP_PORT` и `POSTGRES_PORT`.

Администратор создаётся при первом запуске по `ADMIN_EMAIL` / `ADMIN_PASSWORD`. Уже существующий пользователь не получает ADMIN и его пароль не перезаписывается. Публичная регистрация допускает USER (по умолчанию) и SELLER, но не ADMIN.

## Сборка и проверки

Для запуска без Docker image нужны **JDK 21** и доступный Docker daemon для Testcontainers. Maven устанавливается wrapper-скриптом автоматически.

```bash
./mvnw generate-sources    # воспроизводимая генерация интерфейсов и всех DTO
./mvnw clean verify        # генерация, компиляция, интеграционные тесты, executable JAR
```

Генератор привязан к фазе `generate-sources`: отдельная генерация перед сборкой не нужна. Код находится в `target/generated-sources/openapi/`, весь `target/` игнорируется Git. Контроллеры реализуют сгенерированные интерфейсы; ручных API DTO нет.

Тесты поднимают отдельный PostgreSQL 17 через Testcontainers и выполняют настоящие HTTP-запросы к Spring Boot на случайном порту. Проверяются CRUD, фильтры, пагинация, валидация, ошибки, токены и ротация refresh, роли/владельцы, последовательность бизнес-проверок, цены, скидки, состояния, откаты транзакций, JSON-логи и конкурентные запросы. GitHub Actions запускает ту же команду `clean verify`.

Запуск Java-приложения с БД из Compose:

```bash
docker compose up -d db
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

## API

Все бизнес-эндпоинты требуют `Authorization: Bearer <access_token>`. `/auth/*` и скачивание контракта публичны. Полный контракт, ограничения и ответы: [`src/main/resources/openapi/marketplace.yaml`](src/main/resources/openapi/marketplace.yaml).

| Метод | Путь | Доступ / назначение |
|---|---|---|
| POST | `/auth/register` | Регистрация USER / SELLER |
| POST | `/auth/login` | Access + refresh JWT |
| POST | `/auth/refresh` | Одноразовый refresh → новая пара токенов |
| GET | `/products` | Все роли; `page=0`, `size=20` (1–100), `status`, точный `category` |
| GET | `/products/{id}` | Все роли; включая ARCHIVED |
| POST | `/products` | SELLER / ADMIN; владелец — текущий пользователь |
| PUT, DELETE | `/products/{id}` | SELLER — свои, ADMIN — любые; DELETE архивирует |
| POST | `/orders` | USER / ADMIN; заказ текущего пользователя |
| GET, PUT | `/orders/{id}` | USER — свои, ADMIN — любые; SELLER запрещён |
| POST | `/orders/{id}/cancel` | USER — свои, ADMIN — любые |
| PATCH | `/orders/{id}/status` | ADMIN; последовательный переход состояния |
| POST | `/promo-codes` | SELLER / ADMIN |

Пример регистрации и входа:

```bash
curl -i localhost:8080/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"buyer@example.com","password":"Example-password-123","role":"USER"}'
curl -i localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"buyer@example.com","password":"Example-password-123"}'
```

Пример товара и заказа (токены берутся из login):

```bash
curl -i localhost:8080/products -H "Authorization: Bearer $SELLER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Keyboard","description":null,"price":100.00,"stock":10,"category":"electronics","status":"ACTIVE"}'
curl -i localhost:8080/orders -H "Authorization: Bearer $USER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"items":[{"product_id":"<UUID из ответа создания товара>","quantity":2}]}'
```

Ошибки имеют вид `{"error_code":"…","message":"…","details":{…}}`. При валидации `details.fields` содержит список `{field,message}`, при нехватке остатков `details.products` — `{product_id,requested,available}`. Все обязательные коды задания присутствуют; дополнительные коды для auth, конфликтов регистрации, неизвестного пути/метода и ошибок сервера описаны в контракте.

## Архитектура и принятые решения

- Java 21, Spring Boot 3.5, Spring JDBC, PostgreSQL 17, Flyway, OpenAPI Generator. SQL параметризован; BigDecimal используется для денег, UUID — для идентификаторов, TIMESTAMPTZ — для времени.
- `ProductsController`, `OrdersController`, `PromosController`, `AuthController` реализуют сгенерированные API. Бизнес-логика находится в сервисах. `ContractRules` дополняет Jakarta Validation проверками `multipleOf`, дубликатов товаров, null-позиций и отношений между датами, которые генератор не выражает аннотациями. Эти проверки выполняются до вызова бизнес-метода.
- Миграции запускает Flyway при старте. PostgreSQL ENUM/CHECK/FK ограничивают данные; отдельный индекс ускоряет фильтр по status. `created_at` задаётся DEFAULT, `updated_at` обновляется триггером. Частичный уникальный индекс дополнительно запрещает два активных заказа одного владельца.
- Создание/обновление/отмена заказа выполняются в одной транзакции. Порядок блокировок: пользователь-владелец → заказ → товары по отсортированным UUID → промокод. `SELECT FOR UPDATE` предотвращает отрицательные остатки, двойную отмену, гонки лимитов и перерасход промокода. Ошибка после изменения остатков откатывает все записи, включая счётчики и журнал операций.
- CREATE_ORDER и UPDATE_ORDER имеют независимые лимиты `ORDER_INTERVAL` (ISO-8601 duration, по умолчанию `PT1M`). Учитываются только успешные операции. При изменении чужого заказа администратором лимит и журнал относятся к владельцу заказа. Для локальных экспериментов можно задать `PT0S`.
- В одном заказе товар встречается один раз; повторяющийся `product_id` даёт 400. Это исключает обход проверки остатков через дубликаты. Проверка каталога для всех позиций предшествует проверке остатков.
- Цены сохраняются в `price_at_order`. Изменение каталога не меняет заказ; при PUT старые товары сохраняют свой snapshot, новые получают текущую цену. PUT заменяет набор позиций целиком.
- Состояния: `CREATED → PAYMENT_PENDING → PAID → SHIPPED → COMPLETED`. Отмена отдельным POST допускается из CREATED и PAYMENT_PENDING. Дополнительный ADMIN endpoint `/status` позволяет демонстрировать всю модель состояний; он не принимает переход в CANCELED.
- Формулировка задания про процент свыше 70% трактуется буквально: **скидка 0, оплата 100%**. До 70% включительно применяется заданный процент. Фиксированная скидка ограничена суммой заказа. Округление скидки до копеек — HALF_UP.
- При обновлении промокод перепроверяется. Заказ уже владеет одним использованием, поэтому достижение `max_uses` этим заказом не лишает его скидки. При падении суммы ниже минимума промокод снимается и использование освобождается. Истёкший/неактивный промокод даёт 422 с откатом обновления. Отмена освобождает использование даже после истечения срока.
- Денежные значения ограничены NUMERIC(12,2). Переполнение суммы заказа возвращает VALIDATION_ERROR. Нулевая итоговая сумма допустима при фиксированной скидке на всю стоимость.
- Пароли хранятся через BCrypt. Access JWT действует 20 минут, refresh JWT — 14 дней; срок можно изменить через `ACCESS_TTL` / `REFRESH_TTL`. Проверяются подпись, issuer, exp, тип и role. Refresh атомарно отзывается при использовании; в БД хранится только его UUID, не сам JWT. Изменение роли не отзывает уже выданный access-токен: он действует до истечения своего короткого срока.
- Каждый запрос получает новый UUID в `X-Request-Id`. JSON-лог содержит method, endpoint, status_code, duration_ms, user_id, timestamp. Тело POST/PUT/PATCH/DELETE логируется с маскированием password/token/secret; непарсируемые/обрезанные тела не выводятся. Заголовок Authorization не логируется.

## Демонстрация на защите

`python3 scripts/demo.py` создаёт пользователей с уникальными email и выполняет сценарий через HTTP. После создания, изменения и отмены заказа скрипт **делает SELECT через psql** и показывает реальные строки `products`, `orders`, `order_items`, `promo_codes`, `user_operations`. Скрипт можно запускать повторно, он оставляет свои демонстрационные данные и не очищает БД.

Показаны невалидная цена, недостаточные остатки, неверный промокод с откатом резерва, нарушение ролей и владельца, rate limit, snapshot цены, снятие промокода при уменьшении заказа, отмена и повторная отмена, ротация refresh, полная цепочка состояний и мягкое удаление. Демо рассчитано на `ORDER_INTERVAL=PT1M`, как в `.env.example`.

Дополнительные запросы к БД:

```bash
docker compose exec db psql -U marketplace -d marketplace -c 'SELECT installed_rank,version,description,success FROM flyway_schema_history;'
docker compose exec db psql -U marketplace -d marketplace -c 'SELECT id,name,stock,status,seller_id FROM products;'
docker compose exec db psql -U marketplace -d marketplace -c 'SELECT * FROM orders;'
docker compose exec db psql -U marketplace -d marketplace -c 'SELECT * FROM order_items;'
docker compose exec db psql -U marketplace -d marketplace -c 'SELECT code,current_uses,max_uses FROM promo_codes;'
docker compose exec db psql -U marketplace -d marketplace -c 'SELECT * FROM user_operations;'
```

Для разбора кода: контракт → сгенерированный интерфейс → контроллер → `OrderService` → миграция. Стоит уметь объяснить ACID/rollback, блокировки и порядок их взятия, snapshot цены, отличие access/refresh, BCrypt, индексы и кодогенерацию.

Документация технологий: [Spring OpenAPI Generator](https://openapi-generator.tech/docs/generators/spring/), [Flyway в Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [Testcontainers](https://java.testcontainers.org/).
