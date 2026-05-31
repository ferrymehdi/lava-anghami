# lava-anghami

A Plugin for [Lavalink](https://github.com/lavalink-devs/Lavalink) that adds support for streaming audio directly from Anghami. 

## Lavalink Usage

This plugin requires Lavalink `v4` or greater.

### Installation (Automatic via JitPack)
Add the following to your `application.yml`:

```yaml
lavalink:
  plugins:
    - dependency: "com.github.ferrymehdi:lava-anghami:VERSION" # Replace VERSION with the latest release tag
      repository: "https://jitpack.io"

plugins:
  lava-anghami:
    enabled: true
    anghamiToken: "your anghami session id token" # Required
    reqKey: "your anghami request key"            # Required
    resKey: "your anghami response key"           # Required
    language: "en"                                # Optional (Default: en)

```

> **Note:** To get your `anghamiToken`, `reqKey`, and `resKey`, log into Anghami in your browser and check your Local Storage/Cookies via Developer Tools. For a full `application.yml` example, check the repo.

### Manual Installation

Download the latest `.jar` release and place it into your `plugins` folder.

## Supported URLs and Queries

The plugin supports searching and direct URL resolution for Anghami tracks:

* `angsearch:hello adele`
* https://play.anghami.com/song/105381896
* https://play.anghami.com/album/1018732306
* https://play.anghami.com/artist/89236
* https://play.anghami.com/playlist/105381896

---

## Library Usage (LavaPlayer)

If you want to use this as a library in your own Java/Kotlin project using LavaPlayer:

### Gradle

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.ferrymehdi:lava-anghami:VERSION'
}

```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.ferrymehdi</groupId>
    <artifactId>lava-anghami</artifactId>
    <version>VERSION</version>
</dependency>

```

### Code Example: Registering the Source

To register the `AnghamiAudioSourceManager` in your code:

```java
import org.ferrymehdi.plugin.anghami.AnghamiAudioSourceManager;

// Variables you extract from your Anghami web session
String anghamiToken = "YOUR_SESSION_ID";
String reqKey = "YOUR_REQ_KEY";
String resKey = "YOUR_RES_KEY";
String language = "en"; // optional

// Registering the source
playerManager.registerSourceManager(
    new AnghamiAudioSourceManager(anghamiToken, reqKey, resKey, language)
);

```