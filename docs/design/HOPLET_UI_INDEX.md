# HOPLET UI Index

Статус: официальный модульный каталог UI-компонентов Hoplet  
Обновлено: 2026-07-26  
Источник правил: [HOPLET_LIQUID_GLASS.md](../../HOPLET_LIQUID_GLASS.md)

---

## Назначение

Этот индекс связывает все модульные документы UI-системы Hoplet.  
Он не заменяет [HOPLET_LIQUID_GLASS.md](../../HOPLET_LIQUID_GLASS.md), а использует его как единственный источник визуальных правил.

---

## Как пользоваться комплектом

1. Сначала читать [HOPLET_LIQUID_GLASS.md](../../HOPLET_LIQUID_GLASS.md).
2. Затем открыть нужную группу компонентов.
3. Для размеров, отступов, радиусов, типографики и иконок использовать специализированные документы, а не дублировать значения в реализации.
4. Если правило уже зафиксировано в [HOPLET_LIQUID_GLASS.md](../../HOPLET_LIQUID_GLASS.md), документы ниже только уточняют область применения, ограничения и состав компонентов.

---

## Оглавление

| Документ | Что описывает |
| --- | --- |
| [Cards.md](components/Cards.md) | Карточки и специализированные card-patterns |
| [Buttons.md](components/Buttons.md) | Главные, вторичные, текстовые и икон-кнопки |
| [Inputs.md](components/Inputs.md) | Поля ввода, поиск и выбор значения |
| [Navigation.md](components/Navigation.md) | Верхняя и нижняя навигация, поиск, drawer, rail, floating controls |
| [Lists.md](components/Lists.md) | Utility-строки, selectable-элементы, expandable-элементы, лог-строки |
| [Dialogs.md](components/Dialogs.md) | Dialog, BottomSheet, Snackbar, Banner |
| [Indicators.md](components/Indicators.md) | Индикаторы прогресса, загрузки, статуса и компактные маркеры |
| [Layout.md](components/Layout.md) | Заголовки секций, пустые/ошибочные/загрузочные состояния, layout-блоки |
| [Typography.md](components/Typography.md) | Типографическая шкала и правила иерархии текста |
| [Icons.md](components/Icons.md) | Иконографика, размеры, filled/outlined-правила |

---

## Карта зависимостей

- Все документы опираются на [HOPLET_LIQUID_GLASS.md](../../HOPLET_LIQUID_GLASS.md).
- [Cards.md](components/Cards.md), [Buttons.md](components/Buttons.md), [Inputs.md](components/Inputs.md), [Navigation.md](components/Navigation.md), [Lists.md](components/Lists.md), [Dialogs.md](components/Dialogs.md), [Indicators.md](components/Indicators.md) используют [Layout.md](components/Layout.md), [Typography.md](components/Typography.md) и [Icons.md](components/Icons.md) как справочники токенов.
- [Typography.md](components/Typography.md) и [Icons.md](components/Icons.md) задают правила, которые не должны переопределяться в компонентных файлах.

---

## Экранные контексты

В документации используются одни и те же экранные контексты из [HOPLET_LIQUID_GLASS.md](../../HOPLET_LIQUID_GLASS.md):

- главный статусный экран;
- экран списка;
- экран формы;
- экран логов;
- экран настроек;
- модальные сценарии;
- compact, medium и expanded-компоновки.

---

## Правило изменений

- Нельзя создавать новые стили компонентов вне этого комплекта без обновления документации.
- Нельзя добавлять отдельные вариации ради одного экрана, если задачу решает существующий компонент.
- Нельзя переносить правила из одного документа в другой вручную: вместо этого нужно ссылаться на исходный файл.

