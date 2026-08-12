package org.granitesecurity.profile.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("user_file")
@Getter
@Setter
public class UserFile {

    @Id
    private Long id;

    private String username;

    @Column("file_name")
    private String fileName;

    @Column("object_key")
    private String objectKey;

    private String url;

    @Column("content_type")
    private String contentType;

    @Column("content_hash")
    private String contentHash;

    @Column("size_bytes")
    private Long sizeBytes;

    // Whether the owner published this file to their public profile. Only ever
    // visible to a stranger when the profile itself is published, since the
    // public query joins on that (docs/profile/public-profile.md §11).
    private boolean shared;

    @Column("created_at")
    private Instant createdAt;

    public UserFile() {}
}
