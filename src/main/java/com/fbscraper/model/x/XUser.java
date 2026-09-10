package com.fbscraper.model.x;

public record XUser(
        String id,
        String name,
        String username,
        String description,
        String profileImageUrl,
        String location,
        boolean verified,
        boolean protectedAccount,
        int followersCount,
        int followingCount,
        int postCount
) {
    public static XUser minimal(String id) {
        String safeId = id == null ? "" : id;
        return new XUser(safeId, safeId, "", "", null, "", false, false, 0, 0, 0);
    }

    public XUser {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        username = username == null ? "" : username;
        description = description == null ? "" : description;
        location = location == null ? "" : location;
    }
}
