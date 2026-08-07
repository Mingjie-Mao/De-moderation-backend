package campusguard;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to the CampusGuard moderation backend.
 *
 * <p>Written against {@link HttpURLConnection} and {@code org.json}, both of which
 * are part of the Android framework, rather than pulling in Retrofit and a JSON
 * binder. Three endpoints do not repay two new dependencies in a shared build
 * file, and the transport here is about sixty lines of it.
 *
 * <p>Every call runs on a background thread and reports back on the main one,
 * because Android refuses network access on the UI thread and a callback that
 * arrives on a worker cannot touch a view.
 */
public final class CampusGuardApi {

    /**
     * The emulator's alias for the host machine's loopback. A device on the same
     * network needs the host's LAN address here instead; localhost inside the
     * emulator is the emulator.
     */
    public static final String BASE_URL = "http://10.0.2.2:8080";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private static CampusGuardApi instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private String accessToken;

    private CampusGuardApi() {
    }

    public static synchronized CampusGuardApi getInstance() {
        if (instance == null) {
            instance = new CampusGuardApi();
        }
        return instance;
    }

    public boolean isSignedIn() {
        return accessToken != null;
    }

    public void signOut() {
        accessToken = null;
    }

    /** Delivered on the main thread, so an implementation may touch views directly. */
    public interface Callback<T> {
        void onSuccess(T result);

        void onFailure(String message);
    }

    // --- Authentication ---------------------------------------------------------

    public void register(String username, String password, Callback<Void> callback) {
        run(callback, () -> {
            JSONObject body = new JSONObject().put("username", username).put("password", password);
            request("POST", "/api/auth/register", body, false);
            return null;
        });
    }

    public void logIn(String username, String password, Callback<Void> callback) {
        run(callback, () -> {
            JSONObject body = new JSONObject().put("username", username).put("password", password);
            JSONObject response = new JSONObject(request("POST", "/api/auth/login", body, false));
            accessToken = response.getString("accessToken");
            return null;
        });
    }

    // --- Feed -------------------------------------------------------------------

    /**
     * @param cursor the {@code nextCursor} from the previous page, or null for the
     *     first. The backend pages by position rather than by offset, so a post
     *     arriving while the user scrolls does not shift the page under them.
     */
    public void loadFeed(String forumKey, String cursor, int size, Callback<FeedPage> callback) {
        run(callback, () -> {
            StringBuilder path = new StringBuilder("/api/posts?forum=")
                    .append(URLEncoder.encode(forumKey, "UTF-8"))
                    .append("&size=")
                    .append(size);
            if (cursor != null && !cursor.isEmpty()) {
                path.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"));
            }

            JSONObject response = new JSONObject(request("GET", path.toString(), null, false));
            JSONArray items = response.getJSONArray("items");

            List<BackendPost> posts = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                posts.add(BackendPost.from(items.getJSONObject(i)));
            }

            String next = response.isNull("nextCursor") ? null : response.getString("nextCursor");
            return new FeedPage(posts, response.getBoolean("hasMore"), next);
        });
    }

    public void createPost(String forumKey, String title, String body, Callback<BackendPost> callback) {
        run(callback, () -> {
            JSONObject payload = new JSONObject()
                    .put("forumKey", forumKey)
                    .put("title", title)
                    .put("body", body);
            return BackendPost.from(new JSONObject(request("POST", "/api/posts", payload, true)));
        });
    }

    // --- Moderation -------------------------------------------------------------

    /**
     * Files a report. The backend folds several reports about one post into a
     * single moderation case, so reporting something already reported by someone
     * else succeeds; reporting it twice from this account is refused with a 409.
     */
    public void reportPost(String postId, String reason, Callback<Void> callback) {
        run(callback, () -> {
            JSONObject payload = new JSONObject()
                    .put("targetType", "POST")
                    .put("targetId", postId)
                    .put("reason", reason);
            request("POST", "/api/reports", payload, true);
            return null;
        });
    }

    // --- Transport --------------------------------------------------------------

    private interface Work<T> {
        T call() throws Exception;
    }

    private <T> void run(Callback<T> callback, Work<T> work) {
        worker.execute(() -> {
            try {
                T result = work.call();
                main.post(() -> callback.onSuccess(result));
            } catch (Exception failure) {
                String message = failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage();
                main.post(() -> callback.onFailure(message));
            }
        });
    }

    private String request(String method, String path, JSONObject body, boolean authenticated)
            throws IOException {

        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");

            if (authenticated) {
                if (accessToken == null) {
                    throw new IOException("Sign in to the backend first.");
                }
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            }

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                return read(connection.getInputStream());
            }
            throw new IOException(describe(status, read(connection.getErrorStream())));

        } finally {
            connection.disconnect();
        }
    }

    /**
     * The backend answers failures as RFC 7807 problem documents, so the useful
     * sentence is inside the body rather than in the status line. Showing that to
     * the user is the difference between "409" and "you have already reported
     * this content".
     */
    private String describe(int status, String errorBody) {
        if (errorBody != null && !errorBody.isEmpty()) {
            try {
                JSONObject problem = new JSONObject(errorBody);
                String detail = problem.optString("detail", "");
                if (!detail.isEmpty()) {
                    return detail;
                }
                String title = problem.optString("title", "");
                if (!title.isEmpty()) {
                    return title;
                }
            } catch (Exception ignored) {
                // Not a problem document; fall through to the status code.
            }
        }
        return "Request failed with HTTP " + status + ".";
    }

    private String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        }
        return text.toString();
    }

    /** One page of the feed, plus where the next one starts. */
    public static final class FeedPage {
        public final List<BackendPost> posts;
        public final boolean hasMore;
        public final String nextCursor;

        FeedPage(List<BackendPost> posts, boolean hasMore, String nextCursor) {
            this.posts = posts;
            this.hasMore = hasMore;
            this.nextCursor = nextCursor;
        }
    }
}
