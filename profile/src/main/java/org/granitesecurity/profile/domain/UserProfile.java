package org.granitesecurity.profile.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("user_profile")
@Getter
@Setter
public class UserProfile {

    @Id
    private Long id;

    private String username;

    private String email;

    @Column("first_name")
    private String firstName;

    @Column("last_name")
    private String lastName;

    @Column("display_name")
    private String displayName;

    // The public URL segment (docs/profile/public-profile.md D1). Separate from
    // displayName, which is free text and would make a poor path segment. Stored
    // lowercased; unique across all rows whether published or not (D2).
    private String handle;

    // Free text the user wrote to be published. Never rendered as HTML.
    private String bio;

    @Column("public_profile")
    private boolean publicProfile;

    // Which of the two possible pictures wins: UPLOAD, GOOGLE or NONE. The
    // uploaded object survives a switch to GOOGLE so switching back does not
    // mean uploading again (docs/users/user-pic.md D3).
    @Column("avatar_source")
    private String avatarSource = "NONE";

    @Column("avatar_object_key")
    private String avatarObjectKey;

    @Column("uploaded_avatar_url")
    private String uploadedAvatarUrl;

    // A cache of Google's `picture` claim, not an identifier: the URL rotates
    // when the user changes their Google photo and the old one eventually 404s.
    @Column("google_picture_url")
    private String googlePictureUrl;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public UserProfile() {}

    public UserProfile(String username, String email, String firstName, String lastName) {
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
    }
}
