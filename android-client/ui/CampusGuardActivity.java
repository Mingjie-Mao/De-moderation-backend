package com.example.myapplication;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import campusguard.BackendPost;
import campusguard.CampusGuardApi;

/**
 * The forum, served by the CampusGuard moderation backend rather than by the
 * in-memory demo data the rest of this app uses.
 *
 * <p>A separate screen on purpose. {@code dao.model.Post} belongs to the course's
 * data-structures module and is threaded through the feed, the adapters and the
 * moderation tools; swapping its source for a network call would touch all of
 * them at once. This proves the client and the backend talk to each other without
 * destabilising work that is not mine.
 *
 * <p>Reading the feed is open. Posting and reporting need a token, which is what
 * the sign-in row is for.
 */
public class CampusGuardActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 10;

    private final CampusGuardApi api = CampusGuardApi.getInstance();
    private final List<BackendPost> loaded = new ArrayList<>();

    private ArrayAdapter<String> adapter;
    private TextView status;
    private EditText forumField;
    private Button loadMore;
    private String nextCursor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_campusguard);

        status = findViewById(R.id.cgStatus);
        forumField = findViewById(R.id.cgForum);
        loadMore = findViewById(R.id.cgLoadMore);

        EditText username = findViewById(R.id.cgUsername);
        EditText password = findViewById(R.id.cgPassword);
        ListView feed = findViewById(R.id.cgFeed);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        feed.setAdapter(adapter);

        findViewById(R.id.cgRegister).setOnClickListener(v -> api.register(
                username.getText().toString().trim(),
                password.getText().toString(),
                new Toasting<Void>(R.string.cg_msg_registered)));

        findViewById(R.id.cgSignIn).setOnClickListener(v -> api.logIn(
                username.getText().toString().trim(),
                password.getText().toString(),
                new CampusGuardApi.Callback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        status.setText(R.string.cg_status_signed_in);
                        refresh();
                    }

                    @Override
                    public void onFailure(String message) {
                        report(message);
                    }
                }));

        findViewById(R.id.cgRefresh).setOnClickListener(v -> refresh());
        findViewById(R.id.cgNewPost).setOnClickListener(v -> promptForPost());
        loadMore.setOnClickListener(v -> loadPage(nextCursor));

        // Long press rather than a per-row button: the row layout is the platform's
        // own, and reporting should take slightly more intent than a stray tap.
        feed.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < loaded.size()) {
                promptForReport(loaded.get(position));
            }
            return true;
        });

        refresh();
    }

    private void refresh() {
        loaded.clear();
        adapter.clear();
        nextCursor = null;
        loadPage(null);
    }

    private void loadPage(String cursor) {
        String forumKey = forumField.getText().toString().trim();
        if (forumKey.isEmpty()) {
            report(getString(R.string.cg_msg_forum_required));
            return;
        }

        api.loadFeed(forumKey, cursor, PAGE_SIZE, new CampusGuardApi.Callback<CampusGuardApi.FeedPage>() {
            @Override
            public void onSuccess(CampusGuardApi.FeedPage page) {
                for (BackendPost post : page.posts) {
                    loaded.add(post);
                    adapter.add(post.title + "\n" + post.body + "\n— " + post.authorName);
                }
                adapter.notifyDataSetChanged();

                // The cursor is opaque and simply handed back. Paging by position
                // is why a post arriving mid-scroll does not shift this list.
                nextCursor = page.nextCursor;
                loadMore.setEnabled(page.hasMore);

                if (loaded.isEmpty()) {
                    status.setText(R.string.cg_status_empty);
                }
            }

            @Override
            public void onFailure(String message) {
                report(message);
            }
        });
    }

    private void promptForPost() {
        EditText title = new EditText(this);
        title.setHint(R.string.cg_hint_post_title);
        EditText body = new EditText(this);
        body.setHint(R.string.cg_hint_post_body);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 0);
        layout.addView(title);
        layout.addView(body);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cg_action_new_post)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> api.createPost(
                        forumField.getText().toString().trim(),
                        title.getText().toString().trim(),
                        body.getText().toString().trim(),
                        new CampusGuardApi.Callback<BackendPost>() {
                            @Override
                            public void onSuccess(BackendPost post) {
                                Toast.makeText(CampusGuardActivity.this, R.string.cg_msg_posted, Toast.LENGTH_SHORT)
                                        .show();
                                refresh();
                            }

                            @Override
                            public void onFailure(String message) {
                                report(message);
                            }
                        }))
                .show();
    }

    private void promptForReport(BackendPost post) {
        String[] reasons = {"ABUSE", "SPAM", "ILLEGAL", "OTHER"};

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cg_action_report)
                .setItems(reasons, (dialog, which) -> api.reportPost(
                        post.id, reasons[which], new Toasting<Void>(R.string.cg_msg_reported)))
                .show();
    }

    private void report(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * A callback that announces success and surfaces the backend's own sentence on
     * failure. That sentence is the whole reason the API layer unwraps problem
     * documents: "you have already reported this content" is actionable, "HTTP
     * 409" is not.
     */
    private final class Toasting<T> implements CampusGuardApi.Callback<T> {
        private final int successMessage;

        Toasting(int successMessage) {
            this.successMessage = successMessage;
        }

        @Override
        public void onSuccess(T result) {
            Toast.makeText(CampusGuardActivity.this, successMessage, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onFailure(String message) {
            report(message);
        }
    }
}
