package com.example.ui

data class LegalSection(
    val title: String,
    val paragraphs: List<String>
)

data class FaqEntry(
    val question: String,
    val answer: String
)

object LegalContent {

    fun getPrivacyPolicy(lang: AppLanguage): List<LegalSection> {
        return if (lang == AppLanguage.RUSSIAN) getPrivacyPolicyRussian() else getPrivacyPolicyEnglish()
    }

    fun getTermsOfService(lang: AppLanguage): List<LegalSection> {
        return if (lang == AppLanguage.RUSSIAN) getTermsOfServiceRussian() else getTermsOfServiceEnglish()
    }

    fun getFaq(lang: AppLanguage): List<FaqEntry> {
        return if (lang == AppLanguage.RUSSIAN) getFaqRussian() else getFaqEnglish()
    }

    // ==========================================
    // PRIVACY POLICY - ENGLISH
    // ==========================================
    private fun getPrivacyPolicyEnglish(): List<LegalSection> = listOf(
        LegalSection(
            "1. Introduction & Operator",
            listOf(
                "This Privacy Policy describes how HexShard (\"we\", \"our\", or \"the Service\") processes, stores, and protects information when you use our mobile application.",
                "Service Operator: HexShard Project (an open-architecture cryptographic communications initiative).",
                "By registering an account or using HexShard, you acknowledge the data handling practices described in this document."
            )
        ),
        LegalSection(
            "2. Information We Process",
            listOf(
                "• Account Architecture: HexShard utilizes an independent username and password architecture with optional Telegram 2-Factor Authentication (2FA) verification, completely removing reliance on cellular SMS.",
                "• Virtual Number System: Each HexShard account is assigned an internal private virtual number (+999 prefix). This number is a fictional/cryptographic internal identifier. It does NOT receive cellular SMS, make public telephone calls, or expose your physical carrier SIM data.",
                "• Cryptographic Identity: When your account is provisioned, public encryption keys (ECDH secp256r1) and digital signing keys (ECDSA) are published to the secure directory. Private cryptographic keys never leave your device's Android KeyStore.",
                "• End-to-End Encrypted Message Payloads: Peer-to-peer 1-on-1 messages, encrypted voice notes, and media attachments are encrypted on your device prior to transit. HexShard relay servers store encrypted ciphertext blobs solely to facilitate delivery and synchronization.",
                "• Message Metadata: To deliver messages reliably, our servers process transit metadata including sender UID, recipient UID, deterministic conversation ID, client-generated message ID, timestamps, message status (sent, delivered, read), and delivery confirmations.",
                "• Push Notification Tokens: Firebase Cloud Messaging (FCM) device registration tokens are associated with your device installation to deliver incoming message wake-ups. Push payloads do not include plaintext message content.",
                "• Media & Voice Attachments: Photos, videos, and voice recordings are encrypted with symmetric keys derived from peer key exchanges before upload. Cloud storage only holds encrypted ciphertext blobs."
            )
        ),
        LegalSection(
            "3. Local Device Security (SQLCipher)",
            listOf(
                "All locally cached messages, chat previews, and media index entries are stored in a local SQLite database encrypted with 256-bit AES via SQLCipher.",
                "The database encryption passphrase is generated dynamically using cryptographic hardware randomness and preserved securely within Android KeyStore or EncryptedSharedPreferences."
            )
        ),
        LegalSection(
            "4. Cloud Infrastructure & Third-Party Processors",
            listOf(
                "• Supabase / PostgreSQL Authentication: Manages credential authentication, directory lookups, username reservations, and account security without reliance on telecom carriers.",
                "• Telegram Bot Verification: Optional 2FA security gateway allowing account protection and verification codes via the official @HexShardBot.",
                "• Supabase Storage: Stores public directory records, username reservations, encrypted message queues, and encrypted media attachments.",
                "• Firebase Cloud Messaging (FCM): Delivers background data-only wake-up notifications to wake the application on incoming traffic.",
                "• HexShard AI: Powers the optional assistant feature via secure server-side Edge Functions. Standard peer-to-peer encrypted user chats are NEVER transmitted to or processed by AI."
            )
        ),
        LegalSection(
            "5. Purpose of Processing & Data Retention",
            listOf(
                "Data is processed exclusively to provide secure messaging services, authenticate accounts, verify message sender authenticity, prevent replay attacks, and prevent abuse.",
                "Encrypted messages and delivery metadata remain in Supabase queues to synchronize active conversations across your devices until deleted by conversation participants or upon account deletion.",
                "Ephemeral cache files on device are purged during media playback cache rotation or manual cache clearing in app settings."
            )
        ),
        LegalSection(
            "6. Cryptographic Architecture & Limitations",
            listOf(
                "• End-to-End Encryption: Peer messages are encrypted using ECDH on secp256r1 with AES-256-GCM. Each message includes an HMAC integrity tag and a digital signature created with the sender's private key.",
                "• Server Blindness: The server operator cannot decrypt, view, or modify the contents of encrypted peer messages or attachments.",
                "• Security Limitations: E2EE cannot protect against compromised physical devices, malware on user endpoints, compromised operating system keystores, screen recording apps, or physical theft of unlocked devices.",
                "• Metadata Visibility: While message content is encrypted, network authorities and cloud operators can observe communication frequency, approximate payload sizes, IP addresses, and communication endpoints."
            )
        ),
        LegalSection(
            "7. Account Deletion & Rights",
            listOf(
                "You may delete your account at any time via Settings -> Account -> Delete Account.",
                "Deleting an account permanently unlinks your credentials, removes your username reservation, clears your public cryptographic keys from the directory, deletes local database caches, and marks associated server messages as deleted.",
                "For privacy inquiries or compliance requests, contact supportventaxiscorp@gmail.com."
            )
        ),
        LegalSection(
            "8. Changes to Policy & Contact",
            listOf(
                "We may update this Privacy Policy to reflect technical, operational, or legal developments. Continued use of the application after changes constitute acceptance of the updated terms.",
                "Contact: supportventaxiscorp@gmail.com"
            )
        )
    )

    // ==========================================
    // PRIVACY POLICY - RUSSIAN
    // ==========================================
    private fun getPrivacyPolicyRussian(): List<LegalSection> = listOf(
        LegalSection(
            "1. Введение и оператор сервиса",
            listOf(
                "Настоящая Политика конфиденциальности определяет порядок обработки, хранения и защиты информации пользователей приложения HexShard («мы», «наш» или «Сервис»).",
                "Оператор сервиса: HexShard Project (инициатива разработки безопасных средств связи с открытой архитектурой).",
                "Регистрируя учетную запись или используя HexShard, вы соглашаетесь с правилами обработки данных, изложенными в данном документе."
            )
        ),
        LegalSection(
            "2. Обрабатываемая информация",
            listOf(
                "• Архитектура аккаунтов: HexShard использует систему учетных записей по логину и паролю с опциональной двухфакторной аутентификацией (2FA) через Telegram, полностью исключая зависимость от платных SMS операторов связи.",
                "• Система приватных номеров: Каждому аккаунту HexShard назначается внутренний приватный номер (+999). Этот номер является внутренним криптографическим идентификатором. Он НЕ принимает сотовые SMS, не совершает телефонных вызовов в телефонные сети общего пользования и не раскрывает данные SIM-карты.",
                "• Криптографические ключи: При создании аккаунта генерируются открытые ключи шифрования (ECDH secp256r1) и электронной подписи (ECDSA), публикуемые в каталоге. Закрытые (приватные) ключи никогда не покидают Android KeyStore вашего устройства.",
                "• Сквозное шифрование (E2EE) сообщений: Личные сообщения, голосовые заметки и медиафайлы шифруются на вашем устройстве до отправки в сеть. Серверы хранят исключительно зашифрованный шифротекст для доставки и синхронизации.",
                "• Метаданные сообщений: Для надежной доставки сервер обрабатывает служебные метаданные: UID отправителя и получателя, детерминированный ID диалога, клиентский ID сообщения, метки времени и статусы доставки (отправлено, доставлено, прочитано).",
                "• Push-уведомления (FCM): Регистрационные токены устройств Firebase Cloud Messaging используются для фонового пробуждения приложения. Содержимое сообщений в push-уведомления не передается.",
                "• Медиа и голосовые сообщения: Фотографии, видео и аудиозаписи шифруются симметричными ключами перед загрузкой в облачное хранилище."
            )
        ),
        LegalSection(
            "3. Локальное шифрование данных (SQLCipher)",
            listOf(
                "Все локальные копии сообщений, превью чатов и кэш хранятся в локальной базе данных SQLite, зашифрованной 256-битным алгоритмом AES с помощью SQLCipher.",
                "Ключ шифрования базы генерируется криптографически безопасным генератором случайных чисел и защищен в системном хранилище Android KeyStore / EncryptedSharedPreferences."
            )
        ),
        LegalSection(
            "4. Облачная инфраструктура и обработчики",
            listOf(
                "• Supabase / Сервер аутентификации: Управление учетными записями, резервирование логинов и безопасность профилей без зависимости от операторов мобильной связи.",
                "• Верификация Telegram: Шлюз двухфакторной защиты 2FA для подтверждения доступа через официального бота @HexShardBot.",
                "• Supabase Storage: Хранение публичного каталога пользователей, очередь зашифрованных сообщений и зашифрованные медиавложения.",
                "• Firebase Cloud Messaging (FCM): Отправка скрытых сигналов пробуждения при входящих сообщениях.",
                "• HexShard AI: Обеспечивает работу опционального ассистента HexShard AI через защищенные серверные функции. Личные E2EE чаты между пользователями НИКОГДА не передаются и не анализируются сторонними сервисами."
            )
        ),
        LegalSection(
            "5. Цели обработки и сроки хранения",
            listOf(
                "Данные обрабатываются исключительно для обеспечения защищенного обмена сообщениями, аутентификации, верификации подлинности отправителя, защиты от атак повторного воспроизведения и предотвращения злоупотреблений.",
                "Зашифрованные сообщения хранятся для синхронизации диалогов до их удаления участниками беседы либо до удаления учетной записи.",
                "Временные файлы кэша на устройстве очищаются автоматически при ротации или вручную в настройках."
            )
        ),
        LegalSection(
            "6. Криптографические ограничения и безопасность",
            listOf(
                "• Сквозное шифрование: Сообщения шифруются по протоколу ECDH на кривой secp256r1 с алгоритмом AES-256-GCM. Каждое сообщение содержит HMAC-аутентификатор и цифровую подпись приватным ключом отправителя.",
                "• Недоступность серверу: Операторы серверов и третьи лица не имеют технической возможности расшифровать или прочесть ваши личные сообщения.",
                "• Ограничения безопасности: E2EE не защищает при компрометации самого устройства, наличии вредоносного ПО, записи экрана сторонними приложениями или физическом доступе злоумышленника к разблокированному телефону.",
                "• Метаданные: Провайдеры сети и операторы инфраструктуры могут видеть сетевые адреса, объем трафика и время активности."
            )
        ),
        LegalSection(
            "7. Удаление аккаунта и права пользователей",
            listOf(
                "Вы можете удалить аккаунт в любое время через Настройки -> Аккаунт -> Удалить аккаунт.",
                "При удалении аккаунта освобождается имя пользователя, удаляются открытые ключи из каталога, стирается локальная база данных и помечаются на удаление сообщения на сервере.",
                "По всем вопросам конфиденциальности обращайтесь на supportventaxiscorp@gmail.com."
            )
        ),
        LegalSection(
            "8. Изменения политики и контакты",
            listOf(
                "Мы оставляем за собой право обновлять настоящую Политику в связи с изменениями законодательства или функциональности сервиса.",
                "Контактная почта: supportventaxiscorp@gmail.com"
            )
        )
    )

    // ==========================================
    // TERMS OF SERVICE - ENGLISH
    // ==========================================
    private fun getTermsOfServiceEnglish(): List<LegalSection> = listOf(
        LegalSection(
            "1. Acceptance of Terms",
            listOf(
                "By downloading, installing, accessing, or using the HexShard application, you agree to be bound by these Terms of Service. If you do not agree to these terms, do not use the application.",
                "These Terms constitute a direct agreement between you and the HexShard Project team."
            )
        ),
        LegalSection(
            "2. Eligibility & Account Security",
            listOf(
                "You must be at least 13 years old (or the minimum legal age required in your jurisdiction) to use HexShard.",
                "HexShard uses username and password credentials with an internal cryptographic virtual number (+999). Optional Telegram 2FA verification provides enhanced security. You are solely responsible for safeguarding your password and encryption keys."
            )
        ),
        LegalSection(
            "3. Acceptable Use & Prohibited Conduct",
            listOf(
                "You agree not to use HexShard for any unlawful, harassing, fraudulent, or harmful purposes.",
                "Prohibited conduct includes, but is not limited to: sending unsolicited bulk spam, distributing malware or viruses, attempting to compromise our server infrastructure or reverse-engineer the application, impersonating other individuals or entities, and transmitting illegal content.",
                "Violations may result in immediate suspension or permanent termination of access."
            )
        ),
        LegalSection(
            "4. User Content & Intellectual Property",
            listOf(
                "You retain ownership of all text, audio, images, and materials transmitted through HexShard. Because your messages are end-to-end encrypted, we do not monitor or curate private user communication.",
                "All application code, visual designs, brand assets, and logos are the intellectual property of the HexShard Project."
            )
        ),
        LegalSection(
            "5. AI Features & Assistant",
            listOf(
                "HexShard includes an optional HexShard AI assistant (Ventaxis AI & Hexagon AI). AI responses are generated automatically via secure server-side functions and may occasionally be inaccurate or incomplete.",
                "Do not rely on AI output for critical medical, financial, or legal advice."
            )
        ),
        LegalSection(
            "6. Service Availability & Disclaimers",
            listOf(
                "HexShard is provided on an \"AS IS\" and \"AS AVAILABLE\" basis without warranties of any kind, either express or implied.",
                "We do not guarantee uninterrupted, error-free, or fully synchronized operation across all devices and carrier networks at all times."
            )
        ),
        LegalSection(
            "7. Limitation of Liability",
            listOf(
                "To the maximum extent permitted by applicable law, the HexShard Project shall not be liable for any indirect, incidental, punitive, or consequential damages arising from your use of or inability to use the service."
            )
        ),
        LegalSection(
            "8. Termination & Governing Law",
            listOf(
                "We reserve the right to suspend or terminate accounts that violate these terms.",
                "Governing Law: Standard dispute resolution procedures under applicable jurisdiction rules.",
                "Contact: supportventaxiscorp@gmail.com"
            )
        )
    )

    // ==========================================
    // TERMS OF SERVICE - RUSSIAN
    // ==========================================
    private fun getTermsOfServiceRussian(): List<LegalSection> = listOf(
        LegalSection(
            "1. Принятие условий",
            listOf(
                "Загружая, устанавливая или используя приложение HexShard, вы безоговорочно соглашаетесь с настоящими Условиями использования. Если вы не согласны с условиями, прекратите использование приложения.",
                "Настоящие условия являются соглашением между вами и командой разработчиков проекта HexShard Project."
            )
        ),
        LegalSection(
            "2. Требования к пользователю и безопасность",
            listOf(
                "Вам должно быть не менее 13 лет (или минимальный возраст дееспособности в вашей юрисдикции) для использования сервиса.",
                "HexShard использует аутентификацию по имени пользователя и мастер-паролю с внутренним криптографическим номером (+999). Опциональная 2FA верификация через Telegram повышает защиту аккаунта. Вы несете полную ответственность за сохранность вашего мастер-пароля и устройства."
            )
        ),
        LegalSection(
            "3. Правила использования и запрещенные действия",
            listOf(
                "Запрещается использовать HexShard в противоправных, мошеннических или вредоносных целях.",
                "Строго запрещены: массовая рассылка спама, распространение вредоносного ПО, попытки вмешательства в работу серверов, выдача себя за других лиц и передача незаконного контента.",
                "Нарушение правил влечет блокировку или полное удаление учетной записи."
            )
        ),
        LegalSection(
            "4. Пользовательский контент и авторские права",
            listOf(
                "Вы сохраняете все права на текст, аудиозаписи и файлы, отправляемые через HexShard. Так как личные сообщения защищены сквозным шифрованием (E2EE), сервис не модерирует приватные беседы.",
                "Программный код приложения, дизайн, логотипы и графические элементы являются собственностью проекта HexShard Project."
            )
        ),
        LegalSection(
            "5. Функции искусственного интеллекта (HexShard AI)",
            listOf(
                "В приложение встроен опциональный AI-ассистент HexShard AI (Ventaxis AI и Hexagon AI). Ответы ассистента генерируются автоматически через защищенные серверные функции и могут содержать неточности.",
                "Не используйте ответы AI в качестве юридических, медицинских или финансовых консультаций."
            )
        ),
        LegalSection(
            "6. Отказ от гарантий и доступность",
            listOf(
                "Сервис предоставляется по принципу «КАК ЕСТЬ» («AS IS») и «ПО МЕРЕ ДОСТУПНОСТИ» без каких-либо явных или подразумеваемых гарантий.",
                "Мы не гарантируем бесперебойную работу серверов и мгновенную доставку сообщений при проблемах с сетью или сторонними провайдерами."
            )
        ),
        LegalSection(
            "7. Ограничение ответственности",
            listOf(
                "В пределах, разрешенных законодательством, проект HexShard Project не несет ответственности за косвенные, случайные или штрафные убытки, возникшие вследствие использования или невозможности использования приложения."
            )
        ),
        LegalSection(
            "8. Прекращение действия и применимое право",
            listOf(
                "Мы вправе заблокировать доступ пользователям, нарушающим правила сервиса.",
                "Применимое право: Стандартные правила урегулирования споров в соответствии с законодательством.",
                "Поддержка: supportventaxiscorp@gmail.com"
            )
        )
    )

    // ==========================================
    // FAQ - ENGLISH
    // ==========================================
    private fun getFaqEnglish(): List<FaqEntry> = listOf(
        FaqEntry(
            question = "What is HexShard?",
            answer = "HexShard is a private messaging application focusing on speed, minimalist aesthetics, and strong peer-to-peer end-to-end encryption."
        ),
        FaqEntry(
            question = "Is HexShard free?",
            answer = "HexShard is currently free to use with no advertisements or hidden fees. Transparent terms govern any future operational changes without deceptive lifetime marketing claims."
        ),
        FaqEntry(
            question = "How does End-to-End Encryption (E2EE) work?",
            answer = "When you message a contact, the app uses Elliptic Curve Diffie-Hellman (ECDH) on secp256r1 with their public key to derive shared secrets, generating per-message keys for AES-256-GCM encryption. Each message is digitally signed with your ECDSA private key."
        ),
        FaqEntry(
            question = "Can HexShard or server operators read my messages?",
            answer = "No. The server only receives and relays encrypted ciphertext and HMAC verification tags. Without the private keys stored in the user devices' Android KeyStore, messages cannot be decrypted by anyone in transit or at rest on the server."
        ),
        FaqEntry(
            question = "What metadata is visible to servers?",
            answer = "The server observes delivery metadata necessary for routing: sender user ID, recipient user ID, client message ID, delivery status, and timestamps. Plaintext content and message previews are never visible."
        ),
        FaqEntry(
            question = "Where are messages stored?",
            answer = "Locally, messages are stored in an encrypted SQLite database on your device via SQLCipher. Remotely, encrypted ciphertexts are queued in Supabase until delivered."
        ),
        FaqEntry(
            question = "What happens if I lose my phone or reinstall the app?",
            answer = "Cryptographic keys are bound to your physical device. Reinstalling without a private backup will generate a new cryptographic keypair. You will need to re-verify contacts to confirm your new public identity."
        ),
        FaqEntry(
            question = "Are voice messages and photos encrypted?",
            answer = "Yes. Voice recordings and photo/video attachments are encrypted symmetrically with peer-derived keys on your device before uploading to Cloud Storage. Cloud Storage only holds encrypted blobs."
        ),
        FaqEntry(
            question = "Does HexShard include an AI assistant?",
            answer = "HexShard includes an optional built-in assistant, HexShard AI. Only messages you explicitly send inside the HexShard AI chat are processed by the AI service over secure HTTPS. Standard peer-to-peer encrypted chats with other users are NEVER sent to or processed by AI."
        ),
        FaqEntry(
            question = "How do I delete my account?",
            answer = "Navigate to Settings -> Account -> Delete Account. This unbinds your phone number, removes your username reservation, clears your public key from the directory, and wipes local encrypted storage."
        ),
        FaqEntry(
            question = "How do I report a bug or contact support?",
            answer = "You can report a problem via Settings -> Support -> Report a Problem, or directly email supportventaxiscorp@gmail.com."
        ),
        FaqEntry(
            question = "Why can't HexShard guarantee absolute security?",
            answer = "While our cryptography is mathematically robust, end-to-end encryption cannot safeguard against compromised operating systems, malware, spyware, physical device seizure while unlocked, or screen capture by unauthorized applications."
        )
    )

    // ==========================================
    // FAQ - RUSSIAN
    // ==========================================
    private fun getFaqRussian(): List<FaqEntry> = listOf(
        FaqEntry(
            question = "Что такое HexShard?",
            answer = "HexShard — это быстрый мессенджер с акцентом на приватность, минималистичный темный дизайн и криптографическую защиту сквозным шифрованием (E2EE)."
        ),
        FaqEntry(
            question = "HexShard бесплатный?",
            answer = "В настоящее время приложение полностью бесплатно, без рекламы и платных подписок. Мы придерживаемся честной политики без недостоверных обещаний «навсегда»."
        ),
        FaqEntry(
            question = "Как работает сквозное шифрование (E2EE)?",
            answer = "При отправке сообщения используется протокол Диффи-Хеллмана на эллиптических кривых (ECDH secp256r1) для вычисления общего секрета с открытым ключом собеседника. Текст шифруется алгоритмом AES-256-GCM и подписывается цифровой подписью ECDSA."
        ),
        FaqEntry(
            question = "Может ли HexShard или оператор сервера прочесть мои сообщения?",
            answer = "Нет. Сервер получает исключительно зашифрованный шифротекст и тег подлинности. Без закрытого ключа, хранящегося в Android KeyStore вашего устройства, расшифровать сообщения невозможно."
        ),
        FaqEntry(
            question = "Какие метаданные видит сервер?",
            answer = "Сервер видит только технические метаданные маршрутизации: ID отправителя и получателя, ID диалога, время отправки и статус доставки. Текст сообщений серверу недоступен."
        ),
        FaqEntry(
            question = "Где хранятся сообщения?",
            answer = "На устройстве сообщения хранятся в зашифрованной 256-битным AES базе данных SQLCipher. На сервере Supabase сообщения находятся в зашифрованном виде для синхронизации доставки."
        ),
        FaqEntry(
            question = "Что будет, если я потеряю телефон или переустановлю приложение?",
            answer = "Ключи шифрования привязаны к физическому устройству. При переустановке создается новая пара ключей. Собеседники увидят обновленный открытый ключ."
        ),
        FaqEntry(
            question = "Зашифрованы ли голосовые сообщения и фото?",
            answer = "Да. Все аудиозаметки и фотографии шифруются симметричным ключом непосредственно на устройстве перед загрузкой в облачное хранилище."
        ),
        FaqEntry(
            question = "Использует ли HexShard искусственный интеллект?",
            answer = "В приложении есть встроенный ассистент HexShard AI с возможностью выбора персоны (Ventaxis AI или Hexagon AI). Только сообщения, отправленные непосредственно боту HexShard AI, передаются по защищенному протоколу HTTPS для обработки. Ваши личные переписки с другими пользователями НИКОГДА не передаются в ИИ."
        ),
        FaqEntry(
            question = "Как удалить свой аккаунт?",
            answer = "Перейдите в Настройки -> Аккаунт -> Удалить аккаунт. Это освободит ваш username, сотрет открытые ключи из каталога и полностью очистит локальную базу данных."
        ),
        FaqEntry(
            question = "Как сообщить об ошибке или связаться с поддержкой?",
            answer = "Используйте пункт Настройки -> Поддержка -> Сообщить о проблеме или напишите напрямую на почту supportventaxiscorp@gmail.com."
        ),
        FaqEntry(
            question = "Почему невозможно гарантировать абсолютную безопасность?",
            answer = "Криптографические алгоритмы математически надежны, однако сквозное шифрование бессильно против вредоносных программ на самом устройстве, кейлоггеров, перехвата экрана или физического доступа к разблокированному телефону."
        )
    )
}
