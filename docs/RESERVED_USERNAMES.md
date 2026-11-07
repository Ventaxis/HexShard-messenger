# Reserved usernames — выдача зарезервированных имён (инструкция владельца)

Механизм **безопасен при открытом коде**: таблица `reserved_usernames` невидима клиентам (RLS, все гранты отозваны), проверка выполняется серверно, а выдать имя можно только с `service_role` ключом (SQL Editor / service_role запрос).

## Как зарезервировать имя навсегда (без привязки)
SQL Editor (Supabase Dashboard → SQL Editor), выполняется от роли `service_role`:
```sql
INSERT INTO reserved_usernames (username, reason)
VALUES ('mybrand', 'owner-grant')
ON CONFLICT (username) DO NOTHING;
```

## Как выдать имя конкретному пользователю (вставить его ID)
1. Возьми **Account ID** пользователя (это `auth.users.id` = UUID) — он же «ID аккаунта» в профиле приложения.
2. Выполни **RPC** (имя закрепится за ним; при проверке при регистрации/смене имени сервер разрешит его только этому аккаунту):
```sql
SELECT admin_assign_reserved_username('desired_name', 'ПОЛЬЗОВАТЕЛЬ-UUID-ЗДЕСЬ');
```
Если имя уже занято другим — `reserved_for` перезапишется (последний ход владельца всегда выигрывает). Повторный вызов с тем же ID — безопасен (upsert).

## Как это работает на сервере
- При регистрации/создании профиля сервер проверяет `reserved_usernames`: имя занято и `reserved_for` ≠ этот аккаунт → отказ.
- Клиент тоже держит встроенный список брендовых имён (UX-предупреждение), но **сервер — единственный авторитет**.

## Текущий seed-список (нельзя занять без твоего разрешения)
`ventaxis, ventaxisai, ventaxis_ai, hexagon, hexagonai, hexagon_ai, hexshard, hexshardai, hexshard_ai, ventaxiscorp, ventaxis_corp, support, help, security, admin, administrator, moderator, root, system, official, staff, team, corp, ai, ai_assistant, bot, noreply, admin_hexshard`

Дополняй список обычным INSERT — миграция применяет seed идемпотентно (`ON CONFLICT DO NOTHING`).
