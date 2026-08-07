# Android client integration

The client-side half of the system, kept here so this repository is
self-contained. It is *not* built from here.

These files live in the [De-discussion](https://github.com/Mingjie-Mao/De-discussion)
app, on a local `campusguard-api-integration` branch that is deliberately not
pushed: that repository belongs to a university team, and a branch on it is
theirs to accept rather than mine to publish. Its `main` is untouched.

```
campusguard/CampusGuardApi.java     transport, auth, feed, posting, reporting
campusguard/BackendPost.java        the backend's post payload
ui/CampusGuardActivity.java         the screen
ui/activity_campusguard.xml         its layout
ui/item_campusguard_post.xml        one feed row
ui/campusguard_network_security_config.xml
                                    cleartext scoped to the emulator's loopback
```

In the app they sit at:

```
android/app/src/main/java/campusguard/
android/app/src/main/java/com/example/myapplication/CampusGuardActivity.java
android/app/src/main/res/layout/
android/app/src/main/res/xml/
```

Three decisions worth the words:

**A separate screen, not a new data source for the existing feed.** That app's
`Post` model belongs to its course-provided data-structures module and is threaded
through the adapters and the moderation tools. Swapping its source for a network
call would touch all of them, and none of them are mine.

**No Retrofit.** Three endpoints do not repay two new dependencies in a shared
build file. `HttpURLConnection` and `org.json` ship with the framework and come to
about sixty lines here.

**Failures show the backend's own sentence.** It answers errors as RFC 7807
problem documents, so unwrapping `detail` turns "HTTP 409" into "you have already
reported this content".

**It is styled like the rest of the app**, from that app's own card background,
accent colour and text hierarchy, and it follows the light or dark setting
already chosen. A feed served over REST is not a lesser feed, and drawing it in
platform defaults would say otherwise.

Screenshots of it running are in [`../docs/screenshots`](../docs/screenshots).
