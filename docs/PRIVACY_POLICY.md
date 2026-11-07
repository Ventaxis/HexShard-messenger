# Privacy Policy / Политика конфиденциальности — HexShard Messenger

**Effective date / Дата вступления в силу: 2026-11-08. Operator / Оператор: Ventaxis Corp ("we" / «мы»).**

---

## RU — Политика конфиденциальности

**1. Принцип «мы не знаем твоих сообщений».** HexShard — мессенджер со сквозным шифрованием (E2EE). Текстовые сообщения шифруются на устройстве (ECDH P-256 + AES-256-GCM) и хранятся на сервере только в виде шифротекста. Приватные ключи генерируются и хранятся исключительно на устройстве (Android Keystore) и никогда не покидают его. Мы не можем прочитать содержимое твоих обычных чатов.

**2. Какие данные мы всё же обрабатываем (метаданные).** Для доставки сообщений и работы аккаунта сервер хранит: идентификатор аккаунта, имя пользователя (username), хэш пароля, виртуальный номер +999, идентификаторы чатов, метаданные доставки (отправитель, получатель, время, порядковый номер, размер/тип вложения), привязанный Telegram ID (если ты подключил 2FA через бота), аватар и фон профиля (загружаемые тобой файлы). Эти метаданные необходимы для маршрутизации сообщений и не содержат содержания переписки.

**3. Вложения.** Файлы (фото, видео, голосовые) шифруются на устройстве до загрузки (AES-256-GCM) и хранятся в объектном хранилище в зашифрованном виде. Аватары и фоны профилей — по их природе публичная информация, которую ты добровольно публикуешь в профиле; загружай только те изображения, которые готов показать другим пользователям.

**4. AI-ассистенты (Ventaxis AI / Hexagon AI).** Сообщения, отправленные AI-персонам, **не являются E2EE**: они обрабатываются серверной функцией и передаются провайдеру LLM (Groq, США) для генерации ответа. Не отправляй AI-чатам персональные данные третьих лиц, пароли, ключи и иные секреты. Запросы к AI квотируются и логируются в обезличенном виде (ID аккаунта, счётчики).

**5. Хостинг.** Инфраструктура размещена у Supabase (EU, регион eu-west-1 — Ирландия). Резервные копии и инфраструктурные журналы находятся под контролем Supabase.

**6. Что мы НЕ делаем.** Мы не размещаем рекламу, не продаём данные, не передаём их третьим лицам для маркетинга, не встраиваем трекеры и аналитику, не используем Google-сервисы.

**7. Восстановление аккаунта.** Если ты привязал Telegram, ты можешь запросить одноразовый тикет восстановления. Мы храним только SHA-256-хэш тикета, привязанный к аккаунту, с ограниченным сроком действия.

**8. Удаление.** Ты можешь удалить аккаунт целиком (Настройки → Удалить аккаунт): с сервера удаляются профиль, чаты, вложения и ключи; локальная база шифруется и очищается отдельно. Отдельные чаты можно удалить «для себя» или «для обоих» (см. описание функции в приложении).

**9. Права субъекта данных.** Соответствуя GDPR и ФЗ-152 «О персональных данных» по применимому объёму: ты имеешь право на доступ, исправление, удаление (реализовано функцией удаления аккаунта), ограничение обработки и отзыв согласия (прекращение использования сервиса). Запросы: через контакты в разделе «О приложении».

**10. Возраст.** Сервис не предназначен для лиц младше 16 лет (или возраста, установленного законом твоей страны). Если нам станет известно об аккаунте младше этого возраста, он будет удалён.

**11. Изменения политики.** Мы можем обновлять эту политику; актуальная версия всегда в репозитории проекта. Существенные изменения анонсируются в релизах.

---

## EN — Privacy Policy

**1. "We don't know your messages" principle.** HexShard is an end-to-end encrypted (E2EE) messenger. Messages are encrypted on-device (ECDH P-256 + AES-256-GCM) and stored server-side as ciphertext only. Private keys are generated and stored exclusively on-device (Android Keystore) and never leave it. We cannot read the content of your regular chats.

**2. Metadata we do process.** To deliver messages and operate accounts, the server stores: account ID, username, password hash, the +999 virtual number, conversation IDs, delivery metadata (sender, recipient, timestamp, sequence, attachment size/type), the bound Telegram ID (if you enable bot verification), and your avatar/profile background (files you upload). These metadata are required for message routing and contain no conversation content.

**3. Attachments.** Files (photos, video, voice) are encrypted on-device before upload (AES-256-GCM) and stored in object storage encrypted. Avatars and profile backgrounds are by nature public information you voluntarily publish to your profile — upload only images you are comfortable showing to other users.

**4. AI assistants (Ventaxis AI / Hexagon AI).** Messages sent to AI personas are **NOT E2EE**: they are processed by a server function and forwarded to our LLM provider (Groq, US) to generate a reply. Do not send AI chats third parties' personal data, passwords, keys, or other secrets. AI requests are quota-limited and logged in pseudonymous form (account ID, counters).

**5. Hosting.** Infrastructure is hosted on Supabase (EU, eu-west-1 — Ireland).

**6. What we do NOT do.** No ads, no data sales, no marketing transfers, no trackers, no analytics, no Google services.

**7. Account recovery.** If Telegram is bound to your account you may request a one-time recovery ticket. We store only the SHA-256 hash of the ticket, bound to the account, with a limited lifetime.

**8. Deletion.** You may delete your entire account (Settings → Delete Account): profile, chats, attachments and keys are removed server-side; the local database is encrypted and wiped separately. Individual chats can be deleted "for me" or "for both" (see the in-app feature description).

**9. Data subject rights.** Aligned with GDPR and, to the applicable extent, Russian Federal Law 152-FZ "On Personal Data": access, rectification, erasure (implemented by account deletion), restriction of processing, and withdrawal of consent (by ceasing to use the service). Requests: via the contacts in the About section.

**10. Age.** The service is not intended for persons under 16 (or the age required by your local law). Accounts known to be under that age will be removed.

**11. Changes.** This policy may be updated; the current version always lives in the project repository. Material changes are announced in release notes.
