# LicenseSystem

System licencyjny pod pluginy Minecraft/Paper.

## Moduly

- `engine` - glowny silnik do odpalenia na hostingu lub VPS
- `plugin-sdk` - biblioteka do sprawdzania licencji w pluginie
- `plugin-sdk-gradle` - stary plugin Gradle do generowania `license-sdk.properties` (opcjonalny / legacy)


## Integracja pluginu bez pluginu Gradle

Nowy, prostszy tryb integracji nie wymaga juz `plugin-sdk-gradle` ani generowania pliku `license-sdk.properties`.

Wrzucasz samo SDK przez JitPack i w swoim pluginie trzymasz jedna klase z `publicKey`.

### JitPack

Ten projekt jest multi-module, wiec dla pojedynczego SDK uzywasz koordynatow:

```kotlin
repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.sebustian329-lab.TWOJE_REPO:plugin-sdk:TAG")
}
```

Przyklad, jesli repo na GitHub bedzie sie nazywac `LicenseSystem`:

```kotlin
repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.sebustian329-lab.LicenseSystem:plugin-sdk:1.0.0")
}
```

Dodany jest tez `jitpack.yml` z Java 21, zeby build JitPacka wstal od razu.

### Klasa z danymi licencji w pluginie

`src/main/java/twoj/pakiet/MyPluginLicense.java`

```java
package twoj.pakiet;

import dev.licensesystem.sdk.LicensePluginConfiguration;

public final class MyPluginLicense {
    public static final LicensePluginConfiguration CONFIG =
        LicensePluginConfiguration.of("lspub_TU_WKLEJ_PUBLIC_KEY", 5000);

    private MyPluginLicense() {
    }
}
```

### Uzycie w onEnable()

```java
import dev.licensesystem.sdk.PaperLicenseGuard;

@Override
public void onEnable() {
    saveDefaultConfig();
    PaperLicenseGuard.verifyOrDisable(this, MyPluginLicense.CONFIG);
}
```

Mozesz tez bezposrednio przekazac samo `publicKey`:

```java
PaperLicenseGuard.verifyOrDisable(this, "lspub_TU_WKLEJ_PUBLIC_KEY", 5000);
```

To oznacza, ze wszystkie dane potrzebne do walidacji siedza w `publicKey` i jednej klasie pluginu, a nie w `build.gradle`.

## Jak to teraz dziala

Backend nadal ma swoj publiczny adres w `config.json`, ale plugin nie musi juz dostawac:

- `api.public-url`
- `publicValidationToken`

Zamiast tego tworzysz produkt na backendzie i dostajesz:

- `productKey`
- `publicKey`

Plugin uzywa juz tylko tych dwoch wartosci.

## Wymagania

- Java `21`

## Build

```powershell
.\gradlew build
```

Glowny jar silnika:

```text
engine/build/libs/engine-1.0.0-all.jar
```

## Uruchomienie silnika

```powershell
java -jar engine/build/libs/engine-1.0.0-all.jar
```

Przy pierwszym starcie powstanie:

- `config.json`
- `data/license-system.mv.db`

Przykladowy `config.json`:

```json
{
  "server": {
    "host": "0.0.0.0",
    "port": 8080,
    "publicBaseUrl": "https://twoja-domena.pl"
  },
  "security": {
    "managementApiKey": "GENEROWANY_AUTOMATYCZNIE",
    "publicValidationToken": "LEGACY_FALLBACK_TOKEN",
    "managementPanelPassword": "3^PY5r1J2J>X_^!gLcM6-2aR8F"
  },
  "discord": {
    "enabled": false,
    "token": "PUT_DISCORD_BOT_TOKEN_HERE",
    "guildId": 0,
    "commandPrefix": "!license",
    "allowedUserIds": [],
    "allowedRoleIds": []
  },
  "database": {
    "path": "data/license-system",
    "username": "sa",
    "password": ""
  },
  "defaults": {
    "defaultDurationDays": 30,
    "defaultMaxServers": 1
  }
}
```

## Ważne

`server.publicBaseUrl` musi wskazywac publiczny adres backendu, bo z niego generowany jest `publicKey`.

Przyklad:

```text
https://licencje.twojadomena.pl
```

Jesli zmienisz domenę albo publiczny adres backendu, pokaz ponownie produkt i uzyj nowego `publicKey`.

## Komendy konsoli i Discorda

Te same komendy dzialaja w konsoli hostingu i na Discordzie.

### Ogolne

```text
pomoc
stan
config pokaz
config przeladuj
ustaw <pole> <wartosc>
discord pokaz
discord restart
```

### Produkty

```text
produkt utworz <productKey>
produkt pokaz <productKey>
produkty
```

Po `produkt utworz my-plugin` dostaniesz:

```text
productKey=my-plugin
publicKey=lspub_xxxxx
```

### Licencje

```text
utworz <productKey> <owner> [dni|0=na_zawsze] [maxServers]
cofnij <licenseKey>
przywroc <licenseKey>
przedluz <licenseKey> <dni>
pokaz <licenseKey>
lista [productKey]
```

Przyklad:

```text
utworz my-plugin klient123 30 1
```

## Discord

Ustaw:

- `discord.enabled=true`
- `discord.token`
- opcjonalnie `discord.guildId`
- `discord.allowedUserIds` albo `discord.allowedRoleIds`

W Discord Developer Portal wlacz:

- `Message Content Intent`

Bez tego komendy tekstowe nie beda dzialaly poprawnie.

Przyklady:

```text
!license produkt utworz my-plugin
!license utworz my-plugin klient123 30 1
!license lista my-plugin
```

## REST API

API zarzadzania wymaga naglowka:

```text
X-Api-Key: <managementApiKey>
```

Przyklad stworzenia produktu:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8080/api/v1/manage/products" `
  -Headers @{ "X-Api-Key" = "TU_WSTAW_MANAGEMENT_API_KEY" } `
  -ContentType "application/json" `
  -Body '{"productKey":"my-plugin"}'
```

Przyklad stworzenia licencji:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8080/api/v1/manage/licenses" `
  -Headers @{ "X-Api-Key" = "TU_WSTAW_MANAGEMENT_API_KEY" } `
  -ContentType "application/json" `
  -Body '{"productKey":"my-plugin","owner":"klient123","durationDays":30,"maxServers":1}'
```

## Panel WWW

Po starcie backendu masz od razu panel GUI pod tym samym adresem co API:

```text
http://127.0.0.1:8080/
```

lub:

```text
http://127.0.0.1:8080/manage
```


Domyślne hasło panelu w tej poprawionej wersji:

```text
3^PY5r1J2J>X_^!gLcM6-2aR8F
```

REST API nadal obsługuje nagłówek `X-Api-Key: <managementApiKey>` dla skryptów i integracji.
Logujesz się do panelu hasłem `managementPanelPassword` z `config.json` i możesz:

- tworzyć produkty
- tworzyć licencje
- filtrować listę licencji
- edytować ownera, produkt, status, datę wygaśnięcia, `maxServers` i notatki
- cofać, przywracać i przedłużać licencje
- generować gotowy snippet JitPack do pluginu z poziomu WWW (`build.gradle(.kts)`, klasa z `publicKey`, `onEnable`)

Dodatkowe endpointy panelu:

```text
GET  /api/v1/manage/auth/status
POST /api/v1/manage/auth/login
POST /api/v1/manage/auth/logout
POST /api/v1/manage/licenses/{licenseKey}/update
```

Body logowania:

```json
{
  "password": "3^PY5r1J2J>X_^!gLcM6-2aR8F"
}
```

Dodatkowy endpoint do edycji z REST API:

```text
POST /api/v1/manage/licenses/{licenseKey}/update
```

Body JSON:

```json
{
  "productKey": "my-plugin",
  "owner": "klient123",
  "status": "ACTIVE",
  "expiresAt": "2026-12-31T23:59:59Z",
  "maxServers": 2,
  "notes": "VIP"
}
```

## Integracja z pluginem

Najpierw opublikuj artefakty lokalnie:

```powershell
.\gradlew publishToMavenLocal
```

### Najprostsza wersja - tylko `build.gradle.kts`

Nie musisz nic dopisywac do `settings.gradle.kts`.

W `build.gradle.kts` pluginu wstaw:

```kotlin
buildscript {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath("dev.licensesystem:plugin-sdk-gradle:1.0.0")
    }
}

plugins {
    java
}

apply(plugin = "dev.licensesystem.plugin-sdk")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

licenseSdk {
    publicKey.set("TU_WSTAW_publicKey_Z_backendu")
    timeoutMs.set(5000)
}
```

### Wersja `build.gradle` (Groovy)

```groovy
buildscript {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath 'dev.licensesystem:plugin-sdk-gradle:1.0.0'
    }
}

plugins {
    id 'java'
}

apply plugin: 'dev.licensesystem.plugin-sdk'

repositories {
    mavenCentral()
    maven { url 'https://repo.papermc.io/repository/maven-public/' }
}

dependencies {
    compileOnly 'io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT'
}

licenseSdk {
    publicKey = 'TU_WSTAW_publicKey_Z_backendu'
    timeoutMs = 5000
}
```

### Co robi plugin Gradle teraz automatycznie

Nie trzeba juz recznie dopisywac:

- `settings.gradle(.kts)`
- `implementation("dev.licensesystem:plugin-sdk:...")`
- `productKey`

Plugin Gradle sam:

- doda `plugin-sdk` do zaleznosci
- doda domyslne repozytoria (`mavenLocal`, `mavenCentral`, Paper)
- wyciagnie `productKey` z `publicKey`
- wygeneruje `license-sdk.properties`
- dolaczy `plugin-sdk` do finalnego jar pluginu

Jesli chcesz, nadal mozesz wpisac `productKey` recznie:

```kotlin
licenseSdk {
    publicKey.set("TU_WSTAW_publicKey_Z_backendu")
    productKey.set("my-plugin")
}
```

Mozesz tez wylaczyc automatyke:

```kotlin
licenseSdk {
    publicKey.set("TU_WSTAW_publicKey_Z_backendu")
    addDefaultRepositories.set(false)
    addSdkDependency.set(false)
}
```

Kod pluginu:

```java
import dev.licensesystem.sdk.PaperLicenseGuard;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        PaperLicenseGuard.verifyOrDisable(this);
    }
}
```

`config.yml` pluginu:

```yml
license: "LS-XXXXX-XXXXX-XXXXX-XXXXX"
```

## Szybki flow

1. Ustaw `server.publicBaseUrl` na publiczny adres backendu.
2. Uruchom silnik.
3. Zrob `produkt utworz my-plugin`.
4. Skopiuj `publicKey`.
5. Dodaj go do `build.gradle(.kts)` pluginu.
6. Zrob `utworz my-plugin klient123 30 1`.
7. Wstaw klucz licencji do `config.yml` pluginu.

## Dalszy kierunek

- panel WWW do zarzadzania
- osobne tabele klienci / produkty / zamowienia
- statystyki aktywacji i historia walidacji
- slash commandy Discord
