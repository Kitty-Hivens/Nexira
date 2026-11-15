---
title: Translations
description: How to change the launcher's words or add a language of your own, without rebuilding anything.
---

Every word the launcher shows comes from a table, and you can lay your own tables over the ones it ships: change a few words you do not like, finish a translation, or add a whole language. Nothing needs rebuilding. The files are plain text.

## Where the files go

Under the data directory, next to the widgets folder, in a `translations` folder with one folder per language, named by its language tag:

| System  | Path                                                       |
|---------|------------------------------------------------------------|
| Windows | `%LOCALAPPDATA%\Nexira\translations\<tag>\`                |
| macOS   | `~/Library/Application Support/Nexira/translations/<tag>/` |
| Linux   | `~/.local/share/nexira/translations/<tag>/`                |

In each language folder:

- `<namespace>.properties` holds words, one `key=text` a line, in UTF-8. The launcher's own words are the namespace `nexira`. A module's are its id, `nexira.packs` say.
- `language.properties` describes the language, see below. A language the launcher ships needs none.

Your words are laid over the shipped ones key by key, so a file only needs the keys it changes. The keys and the English text of every one of them are in the shipped English table, [`en.properties`](https://github.com/Kitty-Hivens/Nexira/blob/dev/client-i18n/src/jvmSharedMain/resources/i18n/nexira/en.properties). The files are read at startup and again whenever you switch the language.

## Adding a language

A folder named by a tag the launcher does not ship adds that language to the picker in Settings. Describe it in `language.properties`:

```properties
name=Österreichisches Deutsch
plural=one-other
fallback=de
```

- `name` is what the language calls itself, as the picker lists it.
- `plural` names how its numbers agree with words, one of the families below. Without one, only the `other` form of a plural is used.
- `fallback` is where a key your tables lack is looked up next: one language, or several separated by commas, in order.

A widget module can bring a language the same way, with the same file in its jar, and your own `language.properties` for that tag still has the last word, field by field.

A key is looked up in the language, then in each language it falls back to (the first one and everything it falls back to before the second), then in English, then shown as its key. For the example above, a folder named `de-AT`, that is Austrian German, then German, then English. English always comes last whether you name it or not. A language that falls back to itself, to a language that is not installed, or round in a circle has that step skipped, and the log says which.

| Family        | Forms                 | Languages                      |
|---------------|-----------------------|--------------------------------|
| `other`       | other                 | Japanese, Chinese, Korean      |
| `one-other`   | one, other            | English, German, Spanish       |
| `french`      | one (0 and 1), other  | French, Brazilian Portuguese   |
| `east-slavic` | one, few, many, other | Russian                        |
| `west-slavic` | one, few, other       | Czech, Slovak                  |
| `polish`      | one, few, many, other | Polish                         |

## Writing a message

Most texts are plain words. Some take values, written in a small part of ICU MessageFormat:

| Form                                                                     | What it does                                       |
|--------------------------------------------------------------------------|----------------------------------------------------|
| `{0}`, `{1}`                                                             | The values, in the order the launcher gives them   |
| `{0, plural, one {# file} few {# files} many {# files} other {# files}}` | A form by how the number agrees, `#` is the number |
| `{0, plural, =0 {no files} other {# files}}`                             | An exact number first                              |
| `{0, select, smartycraft {SmartyCraft} other {{0}}}`                     | A form by the value's text                         |
| `'{'`, `'}'`, `'#'`                                                      | A literal brace or hash                            |

Every plural and select needs an `other` form. Keep the values the English text uses and use no others: a `{1}` where English only has `{0}` stays as written. An apostrophe is just an apostrophe.

A text that does not follow these rules is shown as written, and the log says which key. A key you leave out is read in the next language of the chain, so a half-finished translation still shows something in every place.
