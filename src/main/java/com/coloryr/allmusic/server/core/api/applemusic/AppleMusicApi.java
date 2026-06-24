package com.coloryr.allmusic.server.core.api.applemusic;

import com.coloryr.allmusic.server.core.IMusicApi;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.coloryr.allmusic.server.core.objs.SearchMusicObj;
import com.coloryr.allmusic.server.core.objs.music.SearchPageObj;
import com.coloryr.allmusic.server.core.objs.music.SongInfoObj;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apple Music catalog provider for AllMusic.
 *
 * <p>This provider intentionally does not attempt to extract Apple Music's protected media stream URLs.
 * Use {@link #getPlaybackRef(String)} with a client-side MusicKit bridge for playback.</p>
 */
public class AppleMusicApi implements IMusicApi {
    public static final String API_ID = "applemusic";

    private static final Pattern NUMERIC_ID = Pattern.compile("^\\d+$");
    private static final Pattern APPLE_MUSIC_URL_ID = Pattern.compile("(?:/song/[^/?#]+/|[?&]i=)(\\d+)");

    private final HttpClient client;
    private final String developerToken;
    private final String storefront;
    private final int searchLimit;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public AppleMusicApi(String developerToken, String storefront) {
        this(developerToken, storefront, 25, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public AppleMusicApi(String developerToken, String storefront, int searchLimit, HttpClient client) {
        this.developerToken = developerToken;
        this.storefront = storefront == null || storefront.isBlank() ? "us" : storefront.toLowerCase(Locale.ROOT);
        this.searchLimit = Math.max(1, Math.min(searchLimit, 25));
        this.client = client;
    }

    @Override
    public String getId() {
        return API_ID;
    }

    @Override
    public SongInfoObj getMusic(String id, String player, boolean isList) {
        return withBusy(() -> {
            AppleMusicSong song = fetchSong(id);
            if (song == null) {
                return null;
            }

            return new SongInfoObj(song.artistName(), song.name(), song.id(), "", player, song.albumName(),
                    isList, song.durationSeconds(), song.artworkUrl(512, 512), false, null, API_ID);
        });
    }

    @Override
    public SearchPageObj search(String[] args, boolean isDefault) {
        String query = resolveSearchQuery(args, isDefault);
        if (query == null || query.isBlank()) {
            return null;
        }

        return withBusy(() -> {
            List<AppleMusicSong> songs = searchSongs(query);
            if (songs.isEmpty()) {
                return null;
            }

            List<SearchMusicObj> results = new ArrayList<>();
            for (AppleMusicSong song : songs) {
                results.add(new SearchMusicObj(song.id(), song.name(), song.artistName(), song.albumName()));
            }

            return new SearchPageObj(results, Math.max(1, (int) Math.ceil(results.size() / 10.0)), API_ID);
        });
    }

    @Override
    public void setList(String id, Object sender) {
        // Apple Music album/playlist queue import should be implemented by the server integration layer,
        // because AllMusic list insertion APIs differ between supported server platforms.
    }

    @Override
    public LyricSave getLyric(String id) {
        return new LyricSave();
    }

    @Override
    public String getPlayUrl(String id) {
        return null;
    }

    public AppleMusicPlaybackRef getPlaybackRef(String id) {
        String musicId = getMusicId(id);
        if (musicId == null) {
            return null;
        }

        return new AppleMusicPlaybackRef(storefront, "song", musicId,
                "https://music.apple.com/" + storefront + "/song/" + musicId);
    }

    @Override
    public boolean isBusy() {
        return busy.get();
    }

    @Override
    public String getMusicId(String arg) {
        if (arg == null || arg.isBlank()) {
            return null;
        }

        String value = arg.trim();
        if (NUMERIC_ID.matcher(value).matches()) {
            return value;
        }

        Matcher matcher = APPLE_MUSIC_URL_ID.matcher(value);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    @Override
    public boolean checkId(String id) {
        return getMusicId(id) != null;
    }

    public AppleMusicSong fetchSong(String id) throws IOException, InterruptedException {
        String musicId = getMusicId(id);
        if (musicId == null) {
            return null;
        }

        URI uri = URI.create("https://api.music.apple.com/v1/catalog/" + storefront
                + "/songs/" + musicId + "?include=artists,albums");
        JsonObject root = getJson(uri);
        JsonArray data = root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            return null;
        }

        return AppleMusicSong.fromJson(data.get(0).getAsJsonObject());
    }

    public List<AppleMusicSong> searchSongs(String query) throws IOException, InterruptedException {
        String term = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.music.apple.com/v1/catalog/" + storefront
                + "/search?term=" + term + "&types=songs&limit=" + searchLimit);
        JsonObject root = getJson(uri);
        JsonObject results = root.getAsJsonObject("results");
        if (results == null) {
            return List.of();
        }

        JsonObject songs = results.getAsJsonObject("songs");
        if (songs == null) {
            return List.of();
        }

        JsonArray data = songs.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            return List.of();
        }

        List<AppleMusicSong> output = new ArrayList<>();
        for (JsonElement item : data) {
            output.add(AppleMusicSong.fromJson(item.getAsJsonObject()));
        }
        return output;
    }

    private JsonObject getJson(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + developerToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Apple Music API request failed with HTTP " + response.statusCode());
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String resolveSearchQuery(String[] args, boolean isDefault) {
        if (args == null || args.length == 0) {
            return null;
        }

        if (isDefault) {
            return args[0];
        }

        return args.length > 1 ? args[1] : args[0];
    }

    private <T> T withBusy(AppleMusicOperation<T> operation) {
        if (!busy.compareAndSet(false, true)) {
            return null;
        }

        try {
            return operation.run();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            busy.set(false);
        }
    }

    private interface AppleMusicOperation<T> {
        T run() throws IOException, InterruptedException;
    }

    public record AppleMusicPlaybackRef(String storefront, String type, String id, String url) {
    }

    public record AppleMusicSong(String id, String name, String artistName, String albumName, long durationSeconds,
                                 String artworkTemplate, String url) {
        public static AppleMusicSong fromJson(JsonObject item) {
            JsonObject attributes = item.getAsJsonObject("attributes");
            return new AppleMusicSong(
                    getString(item, "id"),
                    getString(attributes, "name"),
                    getString(attributes, "artistName"),
                    getString(attributes, "albumName"),
                    Optional.ofNullable(attributes)
                            .map(data -> data.get("durationInMillis"))
                            .filter(JsonElement::isJsonPrimitive)
                            .map(JsonElement::getAsLong)
                            .orElse(0L) / 1000L,
                    artworkTemplate(attributes),
                    getString(attributes, "url")
            );
        }

        public String artworkUrl(int width, int height) {
            if (artworkTemplate == null || artworkTemplate.isBlank()) {
                return "";
            }

            return artworkTemplate.replace("{w}", String.valueOf(width)).replace("{h}", String.valueOf(height));
        }

        private static String artworkTemplate(JsonObject attributes) {
            if (attributes == null) {
                return "";
            }

            JsonObject artwork = attributes.getAsJsonObject("artwork");
            return getString(artwork, "url");
        }

        private static String getString(JsonObject object, String key) {
            if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
                return "";
            }

            return object.get(key).getAsString();
        }
    }
}
