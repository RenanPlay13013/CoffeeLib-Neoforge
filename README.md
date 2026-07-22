# ☕ CoffeeLib

**CoffeeLib** é uma biblioteca de configuração para mods de Minecraft NeoForge 1.21.1. Ela permite que outros mods definam arquivos de configuração no formato YAML de forma declarativa, usando anotações em classes Java.

Cada mod que consome a biblioteca recebe seu próprio gerenciador de configuração isolado, escrevendo em uma subpasta própria dentro de `config/<modId>/`.

---

## Índice

- [Características](#características)
- [Instalação](#instalação)
- [Guia Rápido](#guia-rápido)
- [Referência de Anotações](#referência-de-anotações)
- [Tipos Suportados](#tipos-suportados)
- [Serializadores](#serializadores)
- [Validação](#validação)
- [Hot Reload](#hot-reload)
- [Gerenciamento de Chaves Desconhecidas](#gerenciamento-de-chaves-desconhecidas)
- [API Completa](#api-completa)
- [Exemplo Completo](#exemplo-completo)

---

## Características

- **Declarativo** — defina a estrutura do config com classes Java e anotações
- **Geração automática** — se o arquivo de config não existir, ele é criado com valores padrão e comentários
- **Type-safe** — acesso tipado via `ConfigValue<T>.get()` e `ConfigValue<T>.set(T)`
- **Suporte a records** — serialize records do Java 16+ como seções aninhadas no YAML
- **Suporte a enums** — enums são serializados como strings automaticamente
- **Validação de range** — `@Range(min, max)` para valores numéricos
- **Hot reload** — monitore alterações em arquivos com `WatchServiceManager`
- **"Did you mean?"** — sugere a chave mais próxima quando uma chave desconhecida é encontrada
- **Isolamento por mod** — cada mod tem sua própria pasta `config/<modId>/`
- **SnakeYAML** — usa SnakeYAML 2.2 como parser, bundled via `jarJar`

---

## Instalação

### 1. Adicione o repositório Maven

Em `build.gradle`:

```gradle
repositories {
    maven {
        url = "https://seu-repositorio-maven"
    }
}
```

### 2. Adicione a dependência

```gradle
dependencies {
    implementation "net.loyalnetwork:coffeelib:1.0"
}
```

---

## Guia Rápido

### 1. Crie uma classe de configuração

```java
import net.loyalnetwork.coffeelib.config.annotation.*;
import net.loyalnetwork.coffeelib.config.node.ConfigValue;

@ConfigFile("server.toml") // ← nome do arquivo YAML (sem a pasta)
public class MyConfig {

    @Comment({"Tempo em segundos entre cada salvamento automático.", "Valores muito baixos podem causar lag."})
    public static final ConfigValue<Integer> AUTO_SAVE_INTERVAL = ConfigValue.of(300);

    @Key("player.default-gamemode")
    @Comment("Modo de jogo padrão para novos jogadores.")
    public static final ConfigValue<String> DEFAULT_GAMEMODE = ConfigValue.of("survival");

    @Range(min = 0.1, max = 10.0)
    public static final ConfigValue<Double> MOVE_SPEED = ConfigValue.of(1.0);
}
```

### 2. Inicialize no construtor do seu mod

```java
import net.loyalnetwork.coffeelib.api.CoffeeConfig;
import net.loyalnetwork.coffeelib.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("meumod")
public class MeuMod {

    private static ConfigManager CONFIG;
    private static final Logger LOGGER = LoggerFactory.getLogger("MeuMod");

    public MeuMod() {
        CONFIG = CoffeeConfig.forMod("meumod", LOGGER)
                .register(MyConfig.class)
                .build();
        CONFIG.load();
    }
}
```

### 3. Use os valores no seu código

```java
public void algumMetodo() {
    int interval = MyConfig.AUTO_SAVE_INTERVAL.get();     // 300 (ou o valor do arquivo)
    String gamemode = MyConfig.DEFAULT_GAMEMODE.get();    // "survival"

    if (MyConfig.MOVE_SPEED.get() > 5.0) {
        // aplicar lógica
    }
}
```

### 4. Atualize valores em tempo de execução

```java
MyConfig.AUTO_SAVE_INTERVAL.set(600); // Atualiza em memória e no disco
```

### O arquivo gerado (`config/meumod/server.yml`)

```yaml
# Tempo em segundos entre cada salvamento automático.
# Valores muito baixos podem causar lag.
auto-save-interval: 300

player:
  default-gamemode: "survival"

move-speed: 1.0
```

---

## Referência de Anotações

### `@ConfigFile("nome.yml")`

**Obrigatória.** Define o nome do arquivo de configuração.

| Alvo     | Classe |
|----------|--------|
| Parâmetro | `String value()` — nome do arquivo |

### `@Key("path.to.key")`

**Opcional.** Define o caminho da chave dentro do YAML. Se omitida, o caminho é derivado do nome do campo: letras minúsculas, underscores viram hífens.

| Alvo     | Campo `ConfigValue<?>` |
|----------|------------------------|
| Exemplo | `@Key("player.max-health")` |

| Nome do campo              | Caminho gerado          |
|----------------------------|-------------------------|
| `MAX_PLAYERS`              | `max-players`           |
| `playerDefaultGamemode`    | `playerdefaultgamemode` |
| `@Key("player.default-gamemode")` | `player.default-gamemode` |

### `@Comment({"linha 1", "linha 2"})`

**Opcional.** Adiciona comentários no YAML acima da chave.

| Alvo     | Campo `ConfigValue<?>` |
|----------|------------------------|
| Parâmetro | `String[] value()` — array de linhas de comentário |

### `@Range(min = 0.0, max = 100.0)`

**Opcional.** Valida que o valor numérico está dentro do intervalo especificado. Se estiver fora, um aviso é emitido no log e o valor padrão é mantido.

| Alvo     | Campo `ConfigValue<? extends Number>` |
|----------|---------------------------------------|
| Padrão   | `min = Double.NEGATIVE_INFINITY`, `max = Double.POSITIVE_INFINITY` |

### `@ReloadListener`

**Opcional.** Marca um método **static** que será invocado sempre que a configuração for recarregada (via `ConfigManager.reload()`).

| Alvo     | Método `static` |
|----------|-----------------|

```java
@ReloadListener
public static void onConfigReload() {
    // Reconstruir caches, reiniciar tarefas agendadas, etc.
}
```

### `@SerializeWith(MeuSerializer.class)`

**Opcional.** Especifica um serializador customizado para o campo.

| Alvo     | Campo `ConfigValue<?>` |
|----------|------------------------|
| Parâmetro | `Class<? extends ConfigSerializer<?>> value()` |

---

## Tipos Suportados

### Tipos primitivos e wrapper

| Tipo Java   | YAML                | Serializador padrão       |
|-------------|---------------------|---------------------------|
| `String`    | `"texto"`           | `StringSerializer`        |
| `Integer` / `int` | `42`          | `IntegerSerializer`       |
| `Double` / `double` | `3.14`       | `DoubleSerializer`        |
| `Boolean` / `boolean` | `true` / `false` | `BooleanSerializer` |

### Enums

Enums são serializados automaticamente como strings (usando `Enum.name()`).

```java
public enum Difficulty {
    PEACEFUL, EASY, NORMAL, HARD
}

@ConfigFile("game.yml")
public class GameConfig {
    public static final ConfigValue<Difficulty> DIFFICULTY = ConfigValue.of(Difficulty.NORMAL);
}
```

Resultado:

```yaml
difficulty: "NORMAL"
```

### Records

Records do Java 16+ são serializados como seções aninhadas no YAML.

```java
public record WorldSettings(int maxPlayers, boolean allowFlight, String motd) {}

@ConfigFile("world.yml")
public class WorldConfig {
    public static final ConfigValue<WorldSettings> WORLD_SETTINGS = ConfigValue.of(
        new WorldSettings(20, false, "Bem-vindo!")
    );
}
```

Resultado:

```yaml
world-settings:
  max-players: 20
  allow-flight: false
  motd: "Bem-vindo!"
```

Records podem conter outros records ou tipos conhecidos pelo registry, formando estruturas aninhadas arbitrariamente.

---

## Serializadores

### Serializadores Padrão

Registrados globalmente por `ConfigBootstrap.registerDefaults()`:
- `StringSerializer`
- `IntegerSerializer`
- `DoubleSerializer`
- `BooleanSerializer`

### Serializador Customizado

Implemente a interface `ConfigSerializer<T>`:

```java
import net.loyalnetwork.coffeelib.config.serializer.ConfigSerializer;

public class ColorSerializer implements ConfigSerializer<java.awt.Color> {

    @Override
    public Object serialize(java.awt.Color value) {
        return String.format("#%06X", value.getRGB() & 0xFFFFFF);
    }

    @Override
    public java.awt.Color deserialize(Object value) {
        String hex = value.toString();
        return java.awt.Color.decode(hex);
    }
}
```

Aplicação no campo:

```java
@SerializeWith(ColorSerializer.class)
public static final ConfigValue<java.awt.Color> BACKGROUND_COLOR = ConfigValue.of(java.awt.Color.BLACK);
```

### Registry Global

Você pode registrar serializadores globalmente se preferir:

```java
import net.loyalnetwork.coffeelib.config.serializer.SerializerRegistry;

SerializerRegistry.register(java.awt.Color.class, new ColorSerializer());
```

### Ordem de Resolução do Serializador

1. `@SerializeWith` na anotação do campo
2. `SerializerRegistry.find(type)` — busca no registry global
3. Se o tipo é `Enum` → `EnumSerializer`
4. Se o tipo é `Record` → `RecordSerializer`
5. Nenhum serializador → o valor YAML bruto é usado diretamente

---

## Validação

### Validação de Range com `@Range`

```java
@Range(min = 1, max = 100)
public static final ConfigValue<Integer> MAX_PLAYERS = ConfigValue.of(10);
```

Se o usuário colocar `max-players: 500` no YAML, o log exibirá:

```
WARN: Value 500.0 for 'max-players' in server.yml is out of range [1.0, 100.0]
```

### Validadores Customizados

Implemente a interface `Validator<T>`:

```java
import net.loyalnetwork.coffeelib.config.validation.Validator;
import net.loyalnetwork.coffeelib.config.validation.ValidationException;

public class EvenNumberValidator implements Validator<Integer> {
    @Override
    public void validate(Integer value, String path, String file) throws ValidationException {
        if (value % 2 != 0) {
            throw new ValidationException(
                "Value " + value + " for '" + path + "' in " + file + " must be even."
            );
        }
    }
}
```

> **Nota:** Validadores customizados precisam ser chamados manualmente. A integração com anotações atualmente só cobre `@Range`.

---

## Hot Reload

O `WatchServiceManager` monitora o diretório de configuração e executa um callback quando arquivos são modificados.

```java
import net.loyalnetwork.coffeelib.config.reload.WatchServiceManager;

var watcher = new WatchServiceManager();

// No construtor do mod:
watcher.start(configDir.toPath(), () -> {
    LOGGER.info("Config file changed, reloading...");
    CONFIG.reload();
});

// No shutdown do mod:
watcher.close();
```

### `ConfigManager.reload()`

O método `reload()` executa em ordem:
1. Recarrega o YAML de cada arquivo do disco
2. Re-lê todos os `ConfigValue`s vinculados
3. Invoca todos os métodos marcados com `@ReloadListener`

Se o YAML estiver mal formatado, o estado anterior em memória é preservado.

---

## Gerenciamento de Chaves Desconhecidas

Quando a CoffeeLib carrega um arquivo, ela compara as chaves presentes no YAML com as chaves esperadas (definidas pelos campos anotados).

Se uma chave desconhecida for encontrada, o sistema `DidYouMean` calcula a distância de Levenshtein e sugere a chave mais próxima:

```
WARN: Unknown key 'auto-save-interval' in server.yml — did you mean 'auto-save-interval'?
```

Se nenhuma sugestão for encontrada dentro do limiar:

```
WARN: Unknown key 'foo-bar' in server.yml does not match any known config option.
```

Chaves faltantes são automaticamente adicionadas ao arquivo com seus valores padrão.

---

## API Completa

### `CoffeeConfig`

Ponto de entrada público. Cada mod chama `CoffeeConfig.forMod()` e recebe seu próprio `ConfigManager.Builder`.

| Método | Descrição |
|--------|-----------|
| `CoffeeConfig.forMod(String modId, Logger logger)` | Retorna um `Builder` configurado para `config/<modId>/` |

### `ConfigManager.Builder`

| Método | Descrição |
|--------|-----------|
| `.register(Class<?> configClass)` | Registra uma classe de configuração |
| `.build()` | Constrói o `ConfigManager` |

### `ConfigManager`

| Método | Descrição |
|--------|-----------|
| `.load()` | Carrega todos os arquivos de configuração e bind dos `ConfigValue`s |
| `.reload()` | Recarrega todos os arquivos do disco, re-lê valores e invoca `@ReloadListener`s |

### `ConfigValue<T>`

| Método | Descrição |
|--------|-----------|
| `ConfigValue.of(T defaultValue)` | Cria uma instância com valor padrão |
| `.get()` | Retorna o valor atual em cache |
| `.set(T value)` | Atualiza o valor em memória e persiste no disco |
| `.reload()` | Re-lê o valor do disco |
| `.defaultValue()` | Retorna o valor padrão original |
| `.isBound()` | Retorna se já foi vinculado a uma configuração |

### `WatchServiceManager`

| Método | Descrição |
|--------|-----------|
| `.start(Path dir, Runnable onFileChange)` | Inicia monitoramento |
| `.close()` | Para o monitoramento |

### `SerializerRegistry`

| Método | Descrição |
|--------|-----------|
| `register(Class<T> type, ConfigSerializer<T> serializer)` | Registra serializador global |
| `get(Class<T> type)` | Busca serializador exato |
| `find(Class<T> type)` | Busca serializador, incluindo superclasses e interfaces |
| `has(Class<?> type)` | Verifica se existe serializador |

### `ConfigSerializer<T>`

| Método | Descrição |
|--------|-----------|
| `Object serialize(T value)` | Converte valor Java para objeto YAML-serializável |
| `T deserialize(Object value)` | Converte objeto YAML bruto de volta para tipo Java |

---

## Exemplo Completo

Aqui está um exemplo integrado de um mod que usa todos os recursos principais da CoffeeLib:

```java
// ========== MyModConfig.java ==========
package meumod.config;

import net.loyalnetwork.coffeelib.config.annotation.*;
import net.loyalnetwork.coffeelib.config.node.ConfigValue;

@ConfigFile("meumod.yml")
public class MyModConfig {

    @Comment({"Nível de log do mod.", "Valores: DEBUG, INFO, WARN, ERROR"})
    public static final ConfigValue<LogLevel> LOG_LEVEL = ConfigValue.of(LogLevel.INFO);

    @Key("features.enable-fly")
    @Comment("Permite que jogadores voem no overworld.")
    public static final ConfigValue<Boolean> ENABLE_FLY = ConfigValue.of(false);

    @Range(min = 0.0, max = 1.0)
    @Comment("Chance de drop especial (0.0 a 1.0).")
    public static final ConfigValue<Double> DROP_CHANCE = ConfigValue.of(0.05);

    @Key("sounds.volume.master")
    @Range(min = 0.0, max = 1.0)
    public static final ConfigValue<Double> MASTER_VOLUME = ConfigValue.of(0.8);

    @Key("sounds.volume.music")
    @Range(min = 0.0, max = 1.0)
    public static final ConfigValue<Double> MUSIC_VOLUME = ConfigValue.of(0.5);

    @ReloadListener
    public static void onReload() {
        System.out.println("Configuração recarregada! Log level agora: " + LOG_LEVEL.get());
    }
}

// ========== LogLevel.java ==========
package meumod.config;

public enum LogLevel {
    DEBUG, INFO, WARN, ERROR
}

// ========== MeuMod.java ==========
package meumod;

import meumod.config.MyModConfig;
import net.loyalnetwork.coffeelib.api.CoffeeConfig;
import net.loyalnetwork.coffeelib.config.ConfigManager;
import net.loyalnetwork.coffeelib.config.reload.WatchServiceManager;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("meumod")
public class MeuMod {

    private static ConfigManager CONFIG;
    private static final Logger LOGGER = LoggerFactory.getLogger("meumod");

    public MeuMod() {
        CONFIG = CoffeeConfig.forMod("meumod", LOGGER)
                .register(MyModConfig.class)
                .build();
        CONFIG.load();

        // Hot reload
        var watcher = new WatchServiceManager();
        watcher.start(FMLPaths.CONFIGDIR.get().resolve("meumod"), () -> {
            LOGGER.info("Arquivo alterado, recarregando...");
            CONFIG.reload();
        });
    }
}
```

Arquivo gerado automaticamente em `config/meumod/meumod.yml`:

```yaml
# Nível de log do mod.
# Valores: DEBUG, INFO, WARN, ERROR
log-level: "INFO"

features:
  enable-fly: false

drop-chance: 0.05

sounds:
  volume:
    master: 0.8

    music: 0.5
```

---

## Licença

All Rights Reserved.
