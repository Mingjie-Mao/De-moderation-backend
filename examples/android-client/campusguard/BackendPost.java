package campusguard;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A post as the CampusGuard backend returns it.
 *
 * <p>Deliberately separate from {@code dao.model.Post}, which belongs to the
 * course's data-structures module and carries its own sorted message collection.
 * Making the backend's payload fit that type would drag the network layer into a
 * model it has nothing to do with, and drag that model onto the network.
 */
public final class BackendPost {

    public final String id;
    public final String forumKey;
    public final String title;
    public final String body;
    public final String authorName;
    public final String createdAt;

    private BackendPost(String id, String forumKey, String title, String body, String authorName, String createdAt) {
        this.id = id;
        this.forumKey = forumKey;
        this.title = title;
        this.body = body;
        this.authorName = authorName;
        this.createdAt = createdAt;
    }

    static BackendPost from(JSONObject json) throws JSONException {
        JSONObject author = json.optJSONObject("author");
        return new BackendPost(
                json.getString("id"),
                json.optString("forumKey", ""),
                json.optString("title", ""),
                json.optString("body", ""),
                author == null ? "unknown" : author.optString("username", "unknown"),
                json.optString("createdAt", ""));
    }
}
