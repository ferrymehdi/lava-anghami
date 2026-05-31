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

    public AnghamiAudioSourceManager(String anghamiToken, String reqKey, String resKey, String language){
        if(anghamiToken == null || reqKey == null || resKey == null){
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
        String title = json.optString("title");
        String artist = json.optString("artist");
        String coverId = json.optString("coverArt");
        int duration = (int) json.optDouble("duration", 0) * 1000;
        String id = json.optString("id");

        return new AudioTrackInfo(title, artist, duration,
                id,
                false,
                "https://play.anghami.com/song/" + id,
                "https://artwork.anghcdn.co/webp/?id=" + coverId,
                null);
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
        if (json == null)
            return AudioReference.NO_TRACK;
        System.out.println(json);

        JSONObject jsonObject = new JSONObject(json);
        if (!jsonObject.has("sections")) {
            log.warn("Invalid response from Anghami API for search query: {}", query);
            return AudioReference.NO_TRACK;
        }

        JSONArray sections = jsonObject.getJSONArray("sections");
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.getJSONObject(i);
            if (section.getString("type").equals("genericitem")) {
                JSONArray data = section.getJSONArray("data");
                List<AudioTrack> tracks = new ArrayList<>();

                for (int j = 0; j < data.length(); j++) {
                    JSONObject trackInfo = data.getJSONObject(j);
                    AudioTrackInfo track = parseTrack(trackInfo);
                    tracks.add(new AnghmiAudioTrack(track, this));
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
                    .addHeader("sec-ch-ua", "\"Chromium\";v=\"121\", \"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\"")
                    .addHeader("sec-ch-ua-mobile", "?0")
                    .addHeader("sec-ch-ua-platform", "\"Windows\"")
                    .get()
                    .build();

            Response response = client.newCall(request).execute();
            if (response.code() != 200) {
                System.out.println(response.body().string());
                log.warn("Failed to fetch Data from Anghami track data Status code: {}",
                        response.code());
                return null;
            }
            assert response.body() != null;
            return response.body().string();
        } catch (Exception e) {
            log.error("Failed to fetch Data from Anghami data", e);
            return null;
        }
    }

    private JSONObject getTrackInfo(String id) {
        String url = UriComponentsBuilder.fromHttpUrl(PRIVATE_API_BASE)
                .queryParam("type", "GETsongdata")
                .queryParam("songid", id)
                .queryParam("lang", language)
                .queryParam("language", language)
                .queryParam("output", "jsonhp")
                .queryParam("sid", anghamiToken)
                .toUriString();
        String json = getJson(url);

        if (json == null)
            return null;

        return new JSONObject(json);
    }

    private AudioItem getTrack(String id) throws IOException {
        JSONObject trackInfo = getTrackInfo(id);

        if (trackInfo == null)
            return AudioReference.NO_TRACK;
        return new AnghmiAudioTrack(parseTrack(trackInfo), this);
    }

    private AudioItem getAlbum(String id) throws IOException {
        String url = UriComponentsBuilder.fromHttpUrl(PRIVATE_API_BASE)
                .queryParam("type", "GETalbumdata")
                .queryParam("albumId", id)
                .queryParam("lang", language)
                .queryParam("language", language)
                .queryParam("output", "jsonhp")
                .queryParam("sid", anghamiToken)
                .toUriString();

        String json = getJson(url);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        JSONObject jsonObject = new JSONObject(json);
        if (!jsonObject.has("sections")) {
            log.warn("Invalid response from Anghami API for album ID: {}", id);
            return AudioReference.NO_TRACK;
        }

        String albumTitle = jsonObject.optString("title", "Unknown Album");
        List<AudioTrack> tracks = new ArrayList<>();
        JSONArray sections = jsonObject.getJSONArray("sections");
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.getJSONObject(i);

            if ("song".equals(section.optString("type")) && "album_songs".equals(section.optString("group"))) {
                JSONArray data = section.getJSONArray("data");

                for (int j = 0; j < data.length(); j++) {
                    JSONObject trackInfo = data.getJSONObject(j);
                    if (j == 0 && "Unknown Album".equals(albumTitle)) {
                        albumTitle = trackInfo.optString("album", "Unknown Album");
                    }
                    AudioTrackInfo track = parseTrack(trackInfo);
                    tracks.add(new AnghmiAudioTrack(track, this));
                }
                break;
            }
        }

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist(albumTitle, tracks, null, false);
    }

    private AudioItem getPlaylist(String id) throws IOException {
        return AudioReference.NO_TRACK;
    }

    private AudioItem getArtist(String id) throws IOException {
        return AudioReference.NO_TRACK;
    }

    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
    }

    @Override
    public void shutdown() {
        try {
            this.httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close HTTP interface manager", e);
        }
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> function) {
        httpInterfaceManager.configureRequests(function);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> consumer) {
        httpInterfaceManager.configureBuilder(consumer);
    }

    public String getAnghamiToken(){
        return anghamiToken;
    }

    public String getReqKey(){
        return reqKey;
    }

    public String getResKey(){
        return resKey;
    }

    public AnghamiApi getApi() {
        return this.anghamiApi;
    }
}