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

[ChatFragment](code/ChatFragment.kt) фрагмент, отрисовывающий чат.

[ChatAdapter](code/ChatAdapter.kt) адаптер RecyclerView чата.

[ChatViewModel](code/ChatViewModel.kt) вью модель для фрагмента.

[ChatRepository](code/ChatRepositoryImpl.kt) сущность для постраничнй загрузки сообщений с сервера + отвечает за кэширование.

[Разметка фрагмента](code/chat_fragment.xml)

Чат простой, поддерживаются только текстовые сообщения, без редактирования. Обновление по сигналу с веб сокета. Три основних типа сообщений - пользовательские входящие и исходящие + системные сообщения.
