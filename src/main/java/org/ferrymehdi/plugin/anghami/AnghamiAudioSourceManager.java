package org.ferrymehdi.plugin.anghami;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;


public class AnghamiAudioSourceManager implements AudioSourceManager, HttpConfigurable {
    public static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://)?(www\\.)?play\\.anghami\\.com/(?<type>artist|album|song|playlist)/(?<identifier>[0-9]+)");
    public static final Pattern SHARE_URL_PATTERN = Pattern.compile(
            "(https?://)?(www\\.)?(anghami\\.app\\.link|play\\.anghami\\.com/share/).*");
    public static final String SEARCH_PREFIX = "angsearch:";
    public static final String PRIVATE_API_BASE = "https://coussa.anghami.com/gateway.php";
    public static final String SEARCH_API_BASE = "https://coussa.anghami.com/rest/v2/GETSearchResults.view";
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";

    private static final Logger log = LoggerFactory.getLogger(AnghamiAudioSourceManager.class);
    private final HttpInterfaceManager httpInterfaceManager;
    private final AnghamiApi anghamiApi;

    private final String anghamiToken;
    private final String reqKey;
    private final String resKey;
    private final String language;

    public AnghamiAudioSourceManager(String anghamiToken, String reqKey, String resKey, String language) {
        if (anghamiToken == null || reqKey == null || resKey == null) {
            throw new IllegalStateException("Anghami token, request key and response key must be set");
        }
        this.anghamiToken = anghamiToken;
        this.reqKey = reqKey;
        this.resKey = resKey;
        this.language = (language != null && !language.isEmpty()) ? language : "en";
        this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
        this.anghamiApi = new AnghamiApi(anghamiToken, reqKey, resKey, USER_AGENT);
    }

    public AnghamiAudioSourceManager(String anghamiToken, String reqKey, String resKey){
        this(anghamiToken, reqKey, resKey, "en");
    }


    @Override
    public String getSourceName() {
        return "anghami";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager audioPlayerManager, AudioReference audioReference) {
        String identifier = audioReference.identifier;
        try {
            if (identifier.startsWith(SEARCH_PREFIX)) {
                return this.getSearch(identifier.substring(SEARCH_PREFIX.length()));
            }

            if (SHARE_URL_PATTERN.matcher(identifier).find()) {
                identifier = resolveRedirect(identifier);
            }

            var matcher = URL_PATTERN.matcher(identifier);
            if (!matcher.find()) {
                return null;
            }
            var id = matcher.group("identifier");
            String type = matcher.group("type");
            return switch (type) {
                case "album" -> this.getAlbum(id);
                case "song" -> this.getTrack(id);
                case "playlist" -> this.getPlaylist(id);
                case "artist" -> this.getArtist(id);
                default -> null;
            };
        } catch (Exception e) {
            log.error("Failed to load item with identifier: {}", identifier, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isTrackEncodable(AudioTrack audioTrack) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack audioTrack, DataOutput dataOutput) throws IOException {
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo audioTrackInfo, DataInput dataInput) throws IOException {
        return new AnghmiAudioTrack(audioTrackInfo, this);
    }

    private AudioTrackInfo parseTrack(JSONObject json) {
        String title = json.optString("title", "Unknown Title");
        String artist = json.optString("artist", "Unknown Artist");
        String coverId = json.optString("coverArt");
        String id = json.optString("id");

        long durationMs = 0;

        if (json.has("duration_ms")) {
            Object msObj = json.get("duration_ms");
            if (msObj instanceof Number) {
                durationMs = ((Number) msObj).longValue();
            } else if (msObj instanceof String) {
                try { durationMs = Long.parseLong((String) msObj); } catch (Exception ignored) {}
            }
        }

        if (durationMs == 0 && json.has("duration")) {
            Object durObj = json.get("duration");
            double durSecs = 0;
            if (durObj instanceof Number) {
                durSecs = ((Number) durObj).doubleValue();
            } else if (durObj instanceof String) {
                try { durSecs = Double.parseDouble((String) durObj); } catch (Exception ignored) {}
            }
            durationMs = Math.round(durSecs * 1000.0);
        }

        return new AudioTrackInfo(title, artist, durationMs,
                id, false,
                "https://play.anghami.com/song/" + id,
                coverId == null || coverId.isEmpty() ? null : "https://artwork.anghcdn.co/webp/?id=" + coverId,
                null);
    }

    private String resolveRedirect(String url) {
        try {
            OkHttpClient redirectClient = new OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .build();

            try (Response response = redirectClient.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location != null && !location.isEmpty()) {
                        log.info("Resolved Anghami App Link to: {}", location);
                        return location;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Anghami redirect for: {}", url, e);
        }
        return url;
    }

    private AudioItem getSearch(String query) throws IOException {
        String url = UriComponentsBuilder.fromHttpUrl(SEARCH_API_BASE)
                .queryParam("language", language)
                .queryParam("appsid", this.anghamiToken)
                .queryParam("web2", "true")
                .queryParam("lang", language)
                .queryParam("userlanguageprod", language)
                .queryParam("query", query)
                .queryParam("page", "0")
                .queryParam("filter_type", "song")
                .queryParam("simple_results", "false")
                .queryParam("output", "jsonhp")
                .queryParam("sid", this.anghamiToken)
                .toUriString();
        String json = getJson(url);
        if (json == null) return AudioReference.NO_TRACK;

        JSONObject jsonObject = new JSONObject(json);
        if (!jsonObject.has("sections")) return AudioReference.NO_TRACK;

        JSONArray sections = jsonObject.getJSONArray("sections");
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.getJSONObject(i);
            if ("genericitem".equals(section.optString("type"))) {
                JSONArray data = section.optJSONArray("data");
                if (data == null) continue;

                List<AudioTrack> tracks = new ArrayList<>();
                for (int j = 0; j < data.length(); j++) {
                    tracks.add(new AnghmiAudioTrack(parseTrack(data.getJSONObject(j)), this));
                }

                if (!tracks.isEmpty()) {
                    return new BasicAudioPlaylist("Anghami Search: " + query, tracks, null, true);
                }
            }
        }
        return AudioReference.NO_TRACK;
    }

    private String getJson(String url) {
        if (url.contains("output=jsonhp")) {
            url = url.replace("output=jsonhp", "output=json");
        }
        try {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Accept", "application/json, text/plain, */*")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .addHeader("Referer", "https://play.anghami.com/")
                    .addHeader("Origin", "https://play.anghami.com")
                    .get()
                    .build();

            Response response = client.newCall(request).execute();
            if (response.code() != 200) {
                log.warn("Failed to fetch Data from Anghami track data Status code: {}", response.code());
                return null;
            }
            assert response.body() != null;
            return response.body().string();
        } catch (Exception e) {
            log.error("Failed to fetch Data from Anghami data", e);
            return null;
        }
    }

    private AudioItem getTrack(String id) throws IOException {
        String url = UriComponentsBuilder.fromHttpUrl(PRIVATE_API_BASE)
                .queryParam("type", "GETsongdata")
                .queryParam("songid", id)
                .queryParam("lang", language)
                .queryParam("language", language)
                .queryParam("output", "jsonhp")
                .queryParam("sid", anghamiToken)
                .toUriString();
        String json = getJson(url);

        if (json == null) return AudioReference.NO_TRACK;
        return new AnghmiAudioTrack(parseTrack(new JSONObject(json)), this);
    }

    private AudioItem getCollection(String id, String typeName) {
        String apiType = typeName.equals("album") ? "GETalbumdata" : "GETplaylistdata";
        String idParam = typeName.equals("album") ? "albumId" : "playlistid";

        String url = UriComponentsBuilder.fromHttpUrl(PRIVATE_API_BASE)
                .queryParam("type", apiType)
                .queryParam(idParam, id)
                .queryParam("lang", language)
                .queryParam("language", language)
                .queryParam("output", "jsonhp")
                .queryParam("web2", "true")
                .queryParam("buffered", "1")
                .queryParam("sid", anghamiToken)
                .toUriString();

        String json = getJson(url);
        if (json == null) return AudioReference.NO_TRACK;

        JSONObject jsonObject = new JSONObject(json);
        if ("failed".equals(jsonObject.optString("status")) || jsonObject.has("error")) {
            return AudioReference.NO_TRACK;
        }

        JSONObject metaContainer = jsonObject;
        if (jsonObject.has("playlist")) {
            JSONObject pl = jsonObject.getJSONObject("playlist");
            metaContainer = pl.has("_attributes") ? pl.getJSONObject("_attributes") : pl;
        } else if (jsonObject.has("album")) {
            JSONObject al = jsonObject.getJSONObject("album");
            metaContainer = al.has("_attributes") ? al.getJSONObject("_attributes") : al;
        }

        String collectionTitle = jsonObject.optString("title",
                jsonObject.optString("name",
                        metaContainer.optString("title",
                                metaContainer.optString("name", "Unknown " + typeName))));

        List<AudioTrack> tracks = new ArrayList<>();

        if (metaContainer.has("songbuffers")) {
            JSONArray buffers = metaContainer.optJSONArray("songbuffers");
            if (buffers != null) {
                String orderStr = metaContainer.optString("songorder", jsonObject.optString("songorder", null));

                List<JSONObject> decodedSongs = AnghamiProtobufDecoder.decodeSongBuffers(buffers, orderStr);
                for (JSONObject song : decodedSongs) {
                    tracks.add(new AnghmiAudioTrack(parseTrack(song), this));
                }
            }
        }
        if (tracks.isEmpty()) {
            JSONObject targetObject = null;
            if(jsonObject.has("playlist") && jsonObject.getJSONObject("playlist").has("songs")) {
                targetObject = jsonObject.getJSONObject("playlist").getJSONObject("songs");
            } else if (jsonObject.has("songs")) {
                targetObject = jsonObject.optJSONObject("songs");
            } else if (metaContainer.has("songs")) {
                targetObject = metaContainer.optJSONObject("songs");
            }

            if (targetObject != null) {
                int count = 0;
                while (targetObject.has(String.valueOf(count))) {
                    JSONObject songWrapper = targetObject.getJSONObject(String.valueOf(count));
                    if (songWrapper.has("_attributes")) {
                        tracks.add(new AnghmiAudioTrack(parseTrack(songWrapper.getJSONObject("_attributes")), this));
                    } else {
                        tracks.add(new AnghmiAudioTrack(parseTrack(songWrapper), this));
                    }
                    count++;
                }
            }
        }

        if (tracks.isEmpty() && jsonObject.has("sections")) {
            JSONArray sections = jsonObject.getJSONArray("sections");
            for (int i = 0; i < sections.length(); i++) {
                JSONObject section = sections.getJSONObject(i);

                if (section.optInt("show_recommendations", 0) == 1 || "Recommended songs".equals(section.optString("title"))) {
                    continue;
                }

                String secType = section.optString("type");
                String secGroup = section.optString("group", "");

                if ("song".equals(secType) || "songs".equals(secGroup) || "album_songs".equals(secGroup)) {
                    JSONArray data = section.optJSONArray("data");
                    if (data != null && data.length() > 0) {
                        for (int j = 0; j < data.length(); j++) {
                            tracks.add(new AnghmiAudioTrack(parseTrack(data.getJSONObject(j)), this));
                        }
                        break;
                    }
                }
            }
        }

        if (tracks.isEmpty() && metaContainer.has("data")) {
            JSONArray data = metaContainer.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    tracks.add(new AnghmiAudioTrack(parseTrack(data.getJSONObject(i)), this));
                }
            }
        }

        if (tracks.isEmpty()) {
            log.warn("Could not find any tracks in Anghami {} ID: {}", typeName, id);
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist(collectionTitle, tracks, null, false);
    }

    private AudioItem getAlbum(String id) throws IOException {
        return getCollection(id, "album");
    }

    private AudioItem getPlaylist(String id) throws IOException {
        return getCollection(id, "playlist");
    }

    private AudioItem getArtist(String id) throws IOException {
        String url = UriComponentsBuilder.fromHttpUrl(PRIVATE_API_BASE)
                .queryParam("type", "GETartistprofile")
                .queryParam("artistId", id)
                .queryParam("lang", language)
                .queryParam("language", language)
                .queryParam("output", "jsonhp")
                .queryParam("web2", "true")
                .queryParam("sid", anghamiToken)
                .toUriString();

        String json = getJson(url);
        if (json == null) return AudioReference.NO_TRACK;

        JSONObject jsonObject = new JSONObject(json);
        String artistName = jsonObject.optString("name", jsonObject.optString("title", "Artist Top Tracks"));
        List<AudioTrack> tracks = new ArrayList<>();

        if (jsonObject.has("sections")) {
            JSONArray sections = jsonObject.getJSONArray("sections");
            for (int i = 0; i < sections.length(); i++) {
                JSONObject section = sections.getJSONObject(i);
                if ("song".equals(section.optString("type")) || "songs".equals(section.optString("group"))) {
                    JSONArray data = section.optJSONArray("data");
                    if (data != null) {
                        for (int j = 0; j < data.length(); j++) {
                            tracks.add(new AnghmiAudioTrack(parseTrack(data.getJSONObject(j)), this));
                        }
                        break;
                    }
                }
            }
        } else if (jsonObject.has("data")) {
            JSONArray data = jsonObject.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    tracks.add(new AnghmiAudioTrack(parseTrack(data.getJSONObject(i)), this));
                }
            }
        }

        if (tracks.isEmpty()) return AudioReference.NO_TRACK;
        return new BasicAudioPlaylist(artistName, tracks, null, false);
    }

    public HttpInterface getHttpInterface() { return httpInterfaceManager.getInterface(); }

    @Override
    public void shutdown() {
        try { this.httpInterfaceManager.close(); }
        catch (IOException e) { log.error("Failed to close HTTP interface manager", e); }
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> function) { httpInterfaceManager.configureRequests(function); }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> consumer) { httpInterfaceManager.configureBuilder(consumer); }

    public AnghamiApi getApi() { return this.anghamiApi; }
}
