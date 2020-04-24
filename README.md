# Что это?

Код UI части (фрагмент, модель, адаптер) простого текстового чата для сервиса по созданию неких событий.

Про стэк технологий:

- MVVM + Cicerone
- AndroidX
- Koin
- Room для кэширования

Архитектура приложения сверху вниз:
```
==================================
UI (код чата из репозитория) level
==================================
Resitory/Service layer
==================================
Network layer (REST via Retrofit)
==================================
```

Что в репозитории:

`ChatFragment` фрагмент, отрисовывающий чат.

`ChatAdapter` адаптер RecyclerView чата.

`ChatViewModel` вью модель для фрагмента.

`ChatRepository` сущность для постраничнй загрузки сообщений с сервера + отвечает за кэширование.

Чат простой, поддерживаются только текстовые сообщения, без редактирования. Обновление по сигналу с веб сокета. Три основних типа сообщений - пользовательские входящие и исходящие + системные сообщения.
