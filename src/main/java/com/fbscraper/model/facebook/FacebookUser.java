package com.fbscraper.model.facebook;

public record FacebookUser(
        String id,
        String name,
        String email,
        String pictureUrl
) {
    public static final FacebookUser ANONYMOUS = new FacebookUser("", "Anonymous", null, null);

    public FacebookUser {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? "Anonymous" : name;
        pictureUrl = pictureUrl == null || pictureUrl.isBlank() ? null : pictureUrl;
    }

    public FacebookUser(String id, String name) {
        this(id, name, null, null);
    }

    public FacebookUser(String id, String name, String email) {
        this(id, name, email, null);
    }

    public boolean hasId() {
        return !id.isBlank();
    }

    public boolean hasName() {
        return !name.isBlank() && !name.equalsIgnoreCase("Anonymous");
    }

    public boolean hasPicture() {
        return pictureUrl != null;
    }

    public String displayName() {
        return hasName() ? name : (hasId() ? "User " + id : "Anonymous");
    }

    public String profileUrl() {
        return hasId() ? "https://www.facebook.com/" + id : null;
    }
}
